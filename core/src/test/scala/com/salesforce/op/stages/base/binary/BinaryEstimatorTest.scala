/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 */

package com.salesforce.op.stages.base.binary

import com.salesforce.op.UID
import com.salesforce.op.features.Feature
import com.salesforce.op.features.types._
import com.salesforce.op.test.{TestFeatureBuilder, TestSparkContext}
import com.salesforce.op.utils.spark.RichDataset._
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.sql.Dataset
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Assertions, FlatSpec}


@RunWith(classOf[JUnitRunner])
class BinaryEstimatorTest extends FlatSpec with TestSparkContext with Assertions {

  val (ds, city, country) = TestFeatureBuilder("city", "country",
    Seq(
      (Text("San Francisco"), Text("USA")),
      (Text("Paris"), Text("France")),
      (Text("Austin"), Text("USA")),
      (Text("San Francisco"), Text("USA")),
      (Text("Paris"), Text("USA")),
      (Text("Puerto Arenas"), Text("Chile")),
      (Text("Iquitos"), Text(None))
    )
  )

  val testEstimator: BinaryEstimator[Text, Text, OPVector] = new TestPivotEstimator()

  Spec[BinaryEstimator[_, _, _]] should "throw an error if you try to get the output without setting the inputs" in {
    intercept[java.util.NoSuchElementException](testEstimator.getOutput())
  }

  it should "return a single output feature of the correct type" in {
    val outputFeatures = testEstimator.setInput(city, country).getOutput()
    outputFeatures shouldBe new Feature[OPVector](
      name = testEstimator.outputName,
      originStage = testEstimator,
      isResponse = false,
      parents = Array(city, country)
    )
  }

  it should "return a BinaryModel with the estimator as the parent and the correct function" in {
    val testModel = testEstimator.setInput(city, country).fit(ds)

    testModel.parent shouldBe testEstimator
    testModel.transformFn(Text("San Francisco"), Text("USA")) shouldBe Vectors.dense(1, 0).toOPVector
  }


  it should "create a BinaryModel that uses the specified transform function when fit" in {
    val testModel = testEstimator.setInput(city, country).fit(ds)
    val testDataTransformed = testModel.setInput(city, country).transform(ds)
    val outputFeatures = testEstimator.getOutput()
    val transformedValues = testDataTransformed.collect(city, country, outputFeatures).toList

    testDataTransformed.schema.fields.map(_.name).toSet shouldEqual Set(city.name, country.name, outputFeatures.name)

    transformedValues.toSet shouldEqual Set(
      (Text("San Francisco"), Text("USA"), Vectors.dense(1.0, 0.0).toOPVector),
      (Text("Paris"), Text("France"), Vectors.dense(0.0, 1.0).toOPVector),
      (Text("Austin"), Text("USA"), Vectors.dense(0.0, 1.0).toOPVector),
      (Text("San Francisco"), Text("USA"), Vectors.dense(1.0, 0.0).toOPVector),
      (Text("Paris"), Text("USA"), Vectors.dense(0.0, 1.0).toOPVector),
      (Text("Puerto Arenas"), Text("Chile"), Vectors.dense(0.0, 1.0).toOPVector),
      (Text("Iquitos"), Text(None), Vectors.dense(0.0, 1.0).toOPVector)
    )

  }

  it should "copy itself and the model successfully" in {
    val est = new TestPivotEstimator()
    val mod = new TestPivotModel("", est.operationName, est.uid)

    est.copy(new ParamMap()).uid shouldBe est.uid
    mod.copy(new ParamMap()).uid shouldBe mod.uid
  }
}

class TestPivotEstimator(uid: String = UID[TestPivotEstimator])
  extends BinaryEstimator[Text, Text, OPVector](operationName = "pivot", uid = uid) {

  def fitFn(data: Dataset[(Text#Value, Text#Value)]): BinaryModel[Text, Text, OPVector] = {
    import data.sparkSession.implicits._
    val counts =
      data.map { case (cty, cntry) => Seq(cty, cntry).flatten.mkString(" ") -> 1 }
        .groupByKey(_._1).reduceGroups((a, b) => (a._1, a._2 + b._2)).map(_._2)

    val topValue = counts.collect().minBy(-_._2)._1
    new TestPivotModel(topValue = topValue, operationName = operationName, uid = uid)
  }
}
final class TestPivotModel private[op](val topValue: String, operationName: String, uid: String)
  extends BinaryModel[Text, Text, OPVector](operationName = operationName, uid = uid) {

  def transformFn: (Text, Text) => OPVector = (city: Text, country: Text) => {
    val cityCountry = Seq(city.value, country.value).flatten.mkString(" ")
    val vector = if (topValue == cityCountry) Vectors.dense(1, 0) else Vectors.dense(0, 1)
    vector.toOPVector
  }

}
