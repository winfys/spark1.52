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

package org.apache.spark.mllib.feature

import org.apache.spark.SparkFunSuite
import org.apache.spark.mllib.util.MLlibTestSparkContext

import org.apache.spark.mllib.util.TestingUtils._
import org.apache.spark.util.Utils
/**
 * Word2Vec将词语转换为向量,
 * 通过测量两个向量内积空间的夹角的余弦值来度量它们之间的相似性,
 * Word2Vec 计算单词的向量表示。这种表示的主要优点是相似的词在向量空间中离得近，
 * 这使得向新模式的泛化更容易并且模型估计更鲁棒。向量表示在诸如命名实体识别、歧义消除、句子解析、
 * 打标签以及机器翻译等自然语言处理程序中比较有用.
 */
class Word2VecSuite extends SparkFunSuite with MLlibTestSparkContext {

  // TODO: add more tests

  test("Word2Vec") {
    //首先导入文本文件，然后将数据解析为RDD[Seq[String]]
    val sentence = "a b " * 100 + "a c " * 10
    val localDoc = Seq(sentence, sentence)
    val doc = sc.parallelize(localDoc)
      .map(line => line.split(" ").toSeq)
      //接着构造Word2Vec实例并使用输入数据拟合出Word2VecModel模型
    val model = new Word2Vec().setVectorSize(10).setSeed(42L).fit(doc)
    //显示了指定单词的2个同义词,
    val syms = model.findSynonyms("a", 2)
    assert(syms.length == 2)
    assert(syms(0)._1 == "b")
    assert(syms(1)._1 == "c")

    // Test that model built using Word2Vec, i.e wordVectors and wordIndec
    // and a Word2VecMap give the same values.
    val word2VecMap = model.getVectors
    val newModel = new Word2VecModel(word2VecMap)
    assert(newModel.getVectors.mapValues(_.toSeq) === word2VecMap.mapValues(_.toSeq))
  }

  test("Word2Vec throws exception when vocabulary is empty") {
    intercept[IllegalArgumentException] {
      val sentence = "a b c"
      val localDoc = Seq(sentence, sentence)
      val doc = sc.parallelize(localDoc)
        .map(line => line.split(" ").toSeq)
      new Word2Vec().setMinCount(10).fit(doc)
    }
  }

  test("Word2VecModel") {
    val num = 2
    val word2VecMap = Map(
      ("china", Array(0.50f, 0.50f, 0.50f, 0.50f)),
      ("japan", Array(0.40f, 0.50f, 0.50f, 0.50f)),
      ("taiwan", Array(0.60f, 0.50f, 0.50f, 0.50f)),
      ("korea", Array(0.45f, 0.60f, 0.60f, 0.60f))
    )
    //word2vec是一个将单词转换成向量形式的工具。
    //可以把对文本内容的处理简化为向量空间中的向量运算，计算出向量空间上的相似度，来表示文本语义上的相似度
    val model = new Word2VecModel(word2VecMap)
    //显示了指定单词的2个同义词,
    val syms = model.findSynonyms("china", num)
    assert(syms.length == num)
    assert(syms(0)._1 == "taiwan")
    assert(syms(1)._1 == "japan")
  }

  test("model load / save") {

    val word2VecMap = Map(
      ("china", Array(0.50f, 0.50f, 0.50f, 0.50f)),
      ("japan", Array(0.40f, 0.50f, 0.50f, 0.50f)),
      ("taiwan", Array(0.60f, 0.50f, 0.50f, 0.50f)),
      ("korea", Array(0.45f, 0.60f, 0.60f, 0.60f))
    )
    
    val model = new Word2VecModel(word2VecMap)

    val tempDir = Utils.createTempDir()
    val path = tempDir.toURI.toString

    try {
      model.save(sc, path)
      val sameModel = Word2VecModel.load(sc, path)
      assert(sameModel.getVectors.mapValues(_.toSeq) === model.getVectors.mapValues(_.toSeq))
    } finally {
      Utils.deleteRecursively(tempDir)
    }

  }
}
