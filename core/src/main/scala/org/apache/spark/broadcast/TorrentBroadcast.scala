/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.broadcast

import java.io._
import java.nio.ByteBuffer

import scala.collection.JavaConversions.asJavaEnumeration
import scala.reflect.ClassTag
import scala.util.Random

import org.apache.spark.{Logging, SparkConf, SparkEnv, SparkException}
import org.apache.spark.io.CompressionCodec
import org.apache.spark.serializer.Serializer
import org.apache.spark.storage.{BroadcastBlockId, StorageLevel}
import org.apache.spark.util.{ByteBufferInputStream, Utils}
import org.apache.spark.util.io.ByteArrayChunkOutputStream

/**
 * A BitTorrent-like implementation of [[org.apache.spark.broadcast.Broadcast]].
 *
 * The mechanism is as follows:
 *
 * The driver divides the serialized object into small chunks and
 * stores those chunks in the BlockManager of the driver.
 *
 * On each executor, the executor first attempts to fetch the object from its BlockManager. If
 * it does not exist, it then uses remote fetches to fetch the small chunks from the driver and/or
 * other executors if available. Once it gets the chunks, it puts the chunks in its own
 * BlockManager, ready for other executors to fetch from.
 *
 * This prevents the driver from being the bottleneck in sending out multiple copies of the
 * broadcast data (one per executor) as done by the [[org.apache.spark.broadcast.HttpBroadcast]].
 *
 * When initialized, TorrentBroadcast objects read SparkEnv.get.conf.
 *
 * @param obj object to broadcast
 * @param id A unique identifier for the broadcast variable.
 */
private[spark] class TorrentBroadcast[T: ClassTag](obj: T, id: Long)
  extends Broadcast[T](id) with Logging with Serializable {

  /**
   * Value of the broadcast object on executors. This is reconstructed by [[readBroadcastBlock]],
   * which builds this value by reading blocks from the driver and/or other executors.
   *
   * On the driver, if the value is required, it is read lazily from the block manager.
   */
  @transient private lazy val _value: T = readBroadcastBlock()

  /** The compression codec to use, or None if compression is disabled */
  @transient private var compressionCodec: Option[CompressionCodec] = _
  /** Size of each block. Default value is 4MB.  This value is only read by the broadcaster. */
  @transient private var blockSize: Int = _
//设置广播配置信息,配置属性确认是否对广播消息进行压缩,并且生成CompressionCode对象
  private def setConf(conf: SparkConf) {
  //广播变量在发送之前是否先要被压缩，通常设置为true是个不错的选择
    compressionCodec = if (conf.getBoolean("spark.broadcast.compress", true)) {
      Some(CompressionCodec.createCodec(conf))
    } else {
      None
    }
    // Note: use getSizeAsKb (not bytes) to maintain compatiblity if no units are provided
    blockSize = conf.getSizeAsKb("spark.broadcast.blockSize", "4m").toInt * 1024
  }
  setConf(SparkEnv.get.conf)
//生成BroadcastBlockId
  private val broadcastId = BroadcastBlockId(id)

  /** Total number of blocks this broadcast variable contains. */
  //块的写入操作,返回广播变更包含的块数
  private val numBlocks: Int = writeBlocks(obj)

  override protected def getValue() = {
    _value
  }

  /**
   * Divide the object into multiple blocks and put those blocks in the block manager.
   * @param value the object to divide
   * @return number of blocks this broadcast variable is divided into
   * 1)将要写入的对象在本地的存储体系中备份一份,以便于Task也可以在本地的Driver上运行
   * 2)给ByteArrayChunkOutputStream指定压缩算法,并且将对象以序列化方式写入ByteChunkOutputStream后转换为Array
   * 3)将每一个ByteBuffer作为一个Block,使用putByte方法写入存储体系.
   */
  private def writeBlocks(value: T): Int = {
    // Store a copy of the broadcast variable in the driver so that tasks run on the driver
    // do not create a duplicate copy of the broadcast variable's value.
    //1)将要写入的对象在本地的存储体系中备份一份,以便于Task也可以在本地的Driver上运行
    SparkEnv.get.blockManager.putSingle(broadcastId, value, StorageLevel.MEMORY_AND_DISK,
      tellMaster = false)
  //2)给ByteArrayChunkOutputStream指定压缩算法,并且将对象以序列化方式写入ByteChunkOutputStream后转换为Array
    val blocks =
      TorrentBroadcast.blockifyObject(value, blockSize, SparkEnv.get.serializer, compressionCodec)
    blocks.zipWithIndex.foreach { case (block, i) =>
      //3)将每一个ByteBuffer作为一个Block,使用putByte方法写入存储体系.
      SparkEnv.get.blockManager.putBytes(
        BroadcastBlockId(id, "piece" + i),
        block,
        StorageLevel.MEMORY_AND_DISK_SER,
        tellMaster = true)
    }
    blocks.length
  }

  /** Fetch torrent blocks from the driver and/or other executors. */
  private def readBlocks(): Array[ByteBuffer] = {
    // Fetch chunks of data. Note that all these chunks are stored in the BlockManager and reported
    // to the driver, so other executors can pull these chunks from this executor as well.
    val blocks = new Array[ByteBuffer](numBlocks)
    val bm = SparkEnv.get.blockManager

    for (pid <- Random.shuffle(Seq.range(0, numBlocks))) {
      val pieceId = BroadcastBlockId(id, "piece" + pid)
      logDebug(s"Reading piece $pieceId of $broadcastId")
      // First try getLocalBytes because there is a chance that previous attempts to fetch the
      // broadcast blocks have already fetched some of the blocks. In that case, some blocks
      // would be available locally (on this executor).
      def getLocal: Option[ByteBuffer] = bm.getLocalBytes(pieceId)
      def getRemote: Option[ByteBuffer] = bm.getRemoteBytes(pieceId).map { block =>
        // If we found the block from remote executors/driver's BlockManager, put the block
        // in this executor's BlockManager.
        SparkEnv.get.blockManager.putBytes(
          pieceId,
          block,
          StorageLevel.MEMORY_AND_DISK_SER,
          tellMaster = true)
        block
      }
      val block: ByteBuffer = getLocal.orElse(getRemote).getOrElse(
        throw new SparkException(s"Failed to get $pieceId of $broadcastId"))
      blocks(pid) = block
    }
    blocks
  }

  /**
   * Remove all persisted state associated with this Torrent broadcast on the executors.
   */
  override protected def doUnpersist(blocking: Boolean) {
    TorrentBroadcast.unpersist(id, removeFromDriver = false, blocking)
  }

  /**
   * Remove all persisted state associated with this Torrent broadcast on the executors
   * and driver.
   */
  override protected def doDestroy(blocking: Boolean) {
    TorrentBroadcast.unpersist(id, removeFromDriver = true, blocking)
  }

  /** Used by the JVM when serializing this object. */
  private def writeObject(out: ObjectOutputStream): Unit = Utils.tryOrIOException {
    assertValid()
    out.defaultWriteObject()
  }

  private def readBroadcastBlock(): T = Utils.tryOrIOException {
    TorrentBroadcast.synchronized {
      setConf(SparkEnv.get.conf)
      SparkEnv.get.blockManager.getLocal(broadcastId).map(_.data.next()) match {
        case Some(x) =>
          x.asInstanceOf[T]

        case None =>
          logInfo("Started reading broadcast variable " + id)
          val startTimeMs = System.currentTimeMillis()
          val blocks = readBlocks()
          logInfo("Reading broadcast variable " + id + " took" + Utils.getUsedTimeMs(startTimeMs))

          val obj = TorrentBroadcast.unBlockifyObject[T](
            blocks, SparkEnv.get.serializer, compressionCodec)
          // Store the merged copy in BlockManager so other tasks on this executor don't
          // need to re-fetch it.
          SparkEnv.get.blockManager.putSingle(
            broadcastId, obj, StorageLevel.MEMORY_AND_DISK, tellMaster = false)
          obj
      }
    }
  }

}


private object TorrentBroadcast extends Logging {
/**
 *blockifyObject方法用于将对象序列化写入ByteChunkOutputStream并用compressionCodec压缩,
      最终将ByteChunkOutputStream转换为Array[ByteBuffer]
 */
  def blockifyObject[T: ClassTag](
      obj: T,
      blockSize: Int,
      serializer: Serializer,
      compressionCodec: Option[CompressionCodec]): Array[ByteBuffer] = {
    val bos = new ByteArrayChunkOutputStream(blockSize)
    val out: OutputStream = compressionCodec.map(c => c.compressedOutputStream(bos)).getOrElse(bos)
    val ser = serializer.newInstance()
    val serOut = ser.serializeStream(out)
    serOut.writeObject[T](obj).close()
    bos.toArrays.map(ByteBuffer.wrap)
  }

  def unBlockifyObject[T: ClassTag](
      blocks: Array[ByteBuffer],
      serializer: Serializer,
      compressionCodec: Option[CompressionCodec]): T = {
    require(blocks.nonEmpty, "Cannot unblockify an empty array of blocks")
    val is = new SequenceInputStream(
      asJavaEnumeration(blocks.iterator.map(block => new ByteBufferInputStream(block))))
    val in: InputStream = compressionCodec.map(c => c.compressedInputStream(is)).getOrElse(is)
    val ser = serializer.newInstance()
    val serIn = ser.deserializeStream(in)
    val obj = serIn.readObject[T]()
    serIn.close()
    obj
  }

  /**
   * Remove all persisted blocks associated with this torrent broadcast on the executors.
   * If removeFromDriver is true, also remove these persisted blocks on the driver.
   */
  def unpersist(id: Long, removeFromDriver: Boolean, blocking: Boolean): Unit = {
    logDebug(s"Unpersisting TorrentBroadcast $id")
    SparkEnv.get.blockManager.master.removeBroadcast(id, removeFromDriver, blocking)
  }
}
