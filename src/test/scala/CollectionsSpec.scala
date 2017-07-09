import java.util.UUID

import akka.testkit.TestActorRef
import com.hypertino.binders.core.BindOptions
import com.hypertino.binders.value._
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model._
import com.hypertino.hyperstorage._
import com.hypertino.hyperstorage.api._
import com.hypertino.hyperstorage.sharding._
import com.hypertino.hyperstorage.workers.primary.{PrimaryTask, PrimaryWorker, PrimaryWorkerTaskResult}
import com.hypertino.hyperstorage.workers.secondary.{BackgroundContentTask, BackgroundContentTaskResult, SecondaryWorker}
import org.scalatest.concurrent.PatienceConfiguration.{Timeout ⇒ TestTimeout}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, FreeSpec, Matchers}

import scala.concurrent.duration._

class CollectionsSpec extends FlatSpec
  with Matchers
  with ScalaFutures
  with CassandraFixture
  with TestHelpers
  with Eventually {

  override implicit val patienceConfig = PatienceConfig(timeout = scaled(Span(10000, Millis)))
  import MessagingContext.Implicits.emptyContext

  "Collection" should "Put item" in {
    val hyperbus = testHyperbus()
    val tk = testKit()
    import tk._

    cleanUpCassandra()

    val worker = TestActorRef(PrimaryWorker.props(hyperbus, db, tracker, 10.seconds))

    val task = ContentPut(
      path = "collection-1~/test-resource-1",
      DynamicBody(Obj.from("text" → "Test item value", "null" → Null))
    )

    db.selectContent("collection-1~", "test-resource-1").futureValue shouldBe None

    val taskStr = task.serializeToString
    worker ! PrimaryTask("collection-1~", System.currentTimeMillis() + 10000, taskStr)
    val backgroundWorkerTask = expectMsgType[BackgroundContentTask]
    backgroundWorkerTask.documentUri should equal("collection-1~")
    val workerResult = expectMsgType[ShardTaskComplete]
    val r = response(workerResult.result.asInstanceOf[PrimaryWorkerTaskResult].content)
    r.headers.statusCode should equal(Status.CREATED)
    r.headers.correlationId should equal(task.correlationId)

    val content = db.selectContent("collection-1~", "test-resource-1").futureValue
    content shouldNot equal(None)
    content.get.body should equal(Some("""{"text":"Test item value","id":"test-resource-1"}"""))
    content.get.transactionList.size should equal(1)
    content.get.revision should equal(1)
    val uuid = content.get.transactionList.head

    val task2 = ContentPut(
      path = "collection-1~/test-resource-2",
      DynamicBody(Obj.from("text" → "Test item value 2"))
    )
    val task2Str = task2.serializeToString
    worker ! PrimaryTask("collection-1~", System.currentTimeMillis() + 10000, task2Str)
    val backgroundWorkerTask2 = expectMsgType[BackgroundContentTask]
    backgroundWorkerTask2.documentUri should equal("collection-1~")
    val workerResult2 = expectMsgType[ShardTaskComplete]
    val r2 = response(workerResult2.result.asInstanceOf[PrimaryWorkerTaskResult].content)
    r2.headers.statusCode should equal(Status.CREATED)
    r2.headers.correlationId should equal(task2.correlationId)

    val content2 = db.selectContent("collection-1~", "test-resource-2").futureValue
    content2 shouldNot equal(None)
    content2.get.body should equal(Some("""{"text":"Test item value 2","id":"test-resource-2"}"""))
    content2.get.transactionList.size should equal(2)
    content2.get.revision should equal(2)

    val transactions = selectTransactions(content2.get.transactionList, "collection-1~", db)
    transactions.size should equal(2)
    transactions.foreach {
      _.completedAt shouldBe None
    }
    transactions.head.revision should equal(2)
    transactions.tail.head.revision should equal(1)

    val backgroundWorker = TestActorRef(SecondaryWorker.props(hyperbus, db, tracker, self, scheduler))
    backgroundWorker ! backgroundWorkerTask
    val backgroundWorkerResult = expectMsgType[ShardTaskComplete]
    val rc = backgroundWorkerResult.result.asInstanceOf[BackgroundContentTaskResult]
    rc.documentUri should equal("collection-1~")
    rc.transactions should equal(content2.get.transactionList.reverse)

    eventually {
      db.selectContentStatic("collection-1~").futureValue.get.transactionList shouldBe empty
    }
    selectTransactions(content2.get.transactionList, "collection-1~", db).foreach {
      _.completedAt shouldNot be(None)
    }
  }

  it should "Patch item" in {
    val hyperbus = testHyperbus()
    val tk = testKit()
    import tk._

    val worker = TestActorRef(PrimaryWorker.props(hyperbus, db, tracker, 10.seconds))

    val path = "collection-1~/test-resource-" + UUID.randomUUID().toString
    val ResourcePath(documentUri, itemId) = ContentLogic.splitPath(path)

    implicit val bindOptions = BindOptions(false) // force null serialization
    val taskPutStr = ContentPut(path,
      DynamicBody(Obj.from("text1" → "abc", "text2" → "klmn"))
    ).serializeToString

    worker ! PrimaryTask(documentUri, System.currentTimeMillis() + 10000, taskPutStr)
    expectMsgType[BackgroundContentTask]
    expectMsgType[ShardTaskComplete]

    val task = ContentPatch(path,
      DynamicBody(Obj.from("text1" → "efg", "text2" → Null, "text3" → "zzz"))
    )
    val taskPatchStr = task.serializeToString
    worker ! PrimaryTask(documentUri, System.currentTimeMillis() + 10000, taskPatchStr)

    expectMsgType[BackgroundContentTask]
    expectMsgPF() {
      case ShardTaskComplete(_, result: PrimaryWorkerTaskResult) if response(result.content).headers.statusCode == Status.OK &&
        response(result.content).headers.correlationId == task.correlationId ⇒ {
        true
      }
    }

    whenReady(db.selectContent(documentUri, itemId)) { result =>
      result.get.body should equal(Some(s"""{"text1":"efg","id":"$itemId","text3":"zzz"}"""))
      result.get.modifiedAt shouldNot be(None)
      result.get.documentUri should equal(documentUri)
      result.get.itemId should equal(itemId)
    }

    // delete element
    val deleteTask = ContentDelete(path)
    val deleteTaskStr = deleteTask.serializeToString
    worker ! PrimaryTask(documentUri, System.currentTimeMillis() + 10000, deleteTaskStr)
    expectMsgType[BackgroundContentTask]
    expectMsgType[ShardTaskComplete]

    // now patch should return 404
    worker ! PrimaryTask(documentUri, System.currentTimeMillis() + 10000, taskPatchStr)

    expectMsgPF() {
      case ShardTaskComplete(_, result: PrimaryWorkerTaskResult) if response(result.content).headers.statusCode == Status.NOT_FOUND &&
        response(result.content).headers.correlationId == task.correlationId ⇒ {
        true
      }
    }
  }

  it should "Delete item" in {
    val hyperbus = testHyperbus()
    val tk = testKit()
    import tk._

    val worker = TestActorRef(PrimaryWorker.props(hyperbus, db, tracker, 10.seconds))

    val path = "collection-1~/test-resource-" + UUID.randomUUID().toString
    val ResourcePath(documentUri, itemId) = ContentLogic.splitPath(path)

    val taskPutStr = ContentPut(path,
      DynamicBody(Obj.from("text1" → "abc", "text2" → "klmn"))
    ).serializeToString

    worker ! PrimaryTask(documentUri, System.currentTimeMillis() + 10000, taskPutStr)
    expectMsgType[BackgroundContentTask]
    expectMsgType[ShardTaskComplete]

    val task = ContentDelete(path)
    val taskStr = task.serializeToString
    worker ! PrimaryTask(documentUri, System.currentTimeMillis() + 10000, taskStr)

    expectMsgType[BackgroundContentTask]
    expectMsgPF() {
      case ShardTaskComplete(_, result: PrimaryWorkerTaskResult) if response(result.content).headers.statusCode == Status.OK &&
        response(result.content).headers.correlationId == task.headers.correlationId ⇒ {
        true
      }
    }

    db.selectContent(documentUri, itemId).futureValue shouldBe None
  }

  it should "Delete collection" in {
    val hyperbus = testHyperbus()
    val tk = testKit()
    import tk._

    val worker = TestActorRef(PrimaryWorker.props(hyperbus, db, tracker, 10.seconds))

    val path = UUID.randomUUID().toString + "~/el1"
    val ResourcePath(documentUri, itemId) = ContentLogic.splitPath(path)

    val taskPutStr = ContentPut(path,
      DynamicBody(Obj.from("text1" → "abc", "text2" → "klmn"))
    ).serializeToString

    worker ! PrimaryTask(documentUri, System.currentTimeMillis() + 10000, taskPutStr)
    expectMsgType[BackgroundContentTask]
    expectMsgType[ShardTaskComplete]

    val task = ContentDelete(documentUri)
    val taskStr = task.serializeToString
    worker ! PrimaryTask(documentUri, System.currentTimeMillis() + 10000, taskStr)

    expectMsgType[BackgroundContentTask]
    expectMsgPF() {
      case ShardTaskComplete(_, result: PrimaryWorkerTaskResult) if response(result.content).headers.statusCode == Status.OK &&
        response(result.content).headers.correlationId == task.headers.correlationId ⇒ {
        true
      }
    }

    db.selectContent(documentUri, itemId).futureValue.get.isDeleted shouldBe true
    db.selectContent(documentUri, "").futureValue.get.isDeleted shouldBe true
  }
}
