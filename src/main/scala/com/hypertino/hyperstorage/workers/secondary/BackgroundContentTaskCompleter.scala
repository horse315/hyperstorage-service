package com.hypertino.hyperstorage.workers.secondary

import java.util.UUID

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import com.datastax.driver.core.utils.UUIDs
import com.hypertino.binders.value.Value
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{DynamicRequest, _}
import com.hypertino.hyperbus.serialization.MessageReader
import com.hypertino.hyperstorage.db.{Transaction, _}
import com.hypertino.hyperstorage.indexing.ItemIndexer
import com.hypertino.hyperstorage.metrics.Metrics
import com.hypertino.hyperstorage.sharding.ShardTaskComplete
import com.hypertino.hyperstorage.utils.FutureUtils
import com.hypertino.hyperstorage.{ResourcePath, _}
import com.hypertino.metrics.MetricsTracker
import monix.execution.Scheduler

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success
import scala.util.control.NonFatal

@SerialVersionUID(1L) case class BackgroundContentTask(ttl: Long, documentUri: String) extends SecondaryTaskTrait {
  def key = documentUri
}

@SerialVersionUID(1L) case class BackgroundContentTaskResult(documentUri: String, transactions: Seq[UUID])

@SerialVersionUID(1L) case class BackgroundContentTaskNoSuchResourceException(documentUri: String) extends RuntimeException(s"No such resource: $documentUri")

@SerialVersionUID(1L) case class BackgroundContentTaskFailedException(documentUri: String, reason: String) extends RuntimeException(s"Background task for $documentUri is failed: $reason")

trait BackgroundContentTaskCompleter extends ItemIndexer {
  def hyperbus: Hyperbus
  def db: Db
  def tracker: MetricsTracker
  def log: LoggingAdapter
  implicit def scheduler: Scheduler

  def deleteIndexDefAndData(indexDef: IndexDef): Future[Unit]

  def executeBackgroundTask(owner: ActorRef, task: BackgroundContentTask): Future[ShardTaskComplete] = {
    try {
      val ResourcePath(documentUri, itemId) = ContentLogic.splitPath(task.documentUri)
      if (!itemId.isEmpty) {
        throw new IllegalArgumentException(s"Background task key ${task.key} doesn't correspond to $documentUri")
      }
      else {
        tracker.timeOfFuture(Metrics.SECONDARY_PROCESS_TIME) {
          db.selectContentStatic(task.documentUri) flatMap {
            case None ⇒
              log.error(s"Didn't found resource to background complete, dismissing task: $task")
              Future.failed(BackgroundContentTaskNoSuchResourceException(task.documentUri))
            case Some(content) ⇒
              try {
                if (log.isDebugEnabled) {
                  log.debug(s"Background task for $content")
                }
                completeTransactions(task, content)
              } catch {
                case NonFatal(e) ⇒
                  log.error(e, s"Background task $task didn't complete")
                  Future.failed(e)
              }
          }
        }
      }
    }
    catch {
      case NonFatal(e) ⇒
        Future.failed(e)
    }
  }

  private def completeTransactions(task: BackgroundContentTask, content: ContentStatic): Future[ShardTaskComplete] = {
    if (content.transactionList.isEmpty) {
      Future.successful(ShardTaskComplete(task, BackgroundContentTaskResult(task.documentUri, Seq.empty)))
    }
    else {
      selectIncompleteTransactions(content) flatMap { incompleteTransactions ⇒
        val updateIndexFuture: Future[Unit] = updateIndexes(content, incompleteTransactions)
        updateIndexFuture.flatMap { _ ⇒
          FutureUtils.serial(incompleteTransactions) { it ⇒
            val event = it.unwrappedBody
            hyperbus.publish(event).runAsync.flatMap { publishResult ⇒
              if (log.isDebugEnabled) {
                log.debug(s"Event $event is published with result $publishResult")
              }
              db.completeTransaction(it.transaction) map { _ ⇒
                if (log.isDebugEnabled) {
                  log.debug(s"${it.transaction} is complete")
                }
                it.transaction
              }
            }
          }
        } map { updatedTransactions ⇒
          ShardTaskComplete(task, BackgroundContentTaskResult(task.documentUri, updatedTransactions.map(_.uuid)))
        } recover {
          case NonFatal(e) ⇒
            log.error(e, s"Task failed: $task")
            ShardTaskComplete(task, BackgroundContentTaskFailedException(task.documentUri, e.toString))
        } andThen {
          case Success(ShardTaskComplete(_, BackgroundContentTaskResult(documentUri, updatedTransactions))) ⇒
            log.debug(s"Removing completed transactions $updatedTransactions from $documentUri")
            db.removeCompleteTransactionsFromList(documentUri, updatedTransactions.toList) recover {
              case NonFatal(e) ⇒
                log.error(e, s"Can't remove complete transactions $updatedTransactions from $documentUri")
            }
        }
      }
    }
  }

  private def selectIncompleteTransactions(content: ContentStatic): Future[Seq[UnwrappedTransaction]] = {
    import ContentLogic._
    val transactionsFStream = content.transactionList.toStream.map { transactionUuid ⇒
      val quantum = TransactionLogic.getDtQuantum(UUIDs.unixTimestamp(transactionUuid))
      db.selectTransaction(quantum, content.partition, content.documentUri, transactionUuid)
    }
    FutureUtils.collectWhile(transactionsFStream) {
      case Some(transaction) ⇒ UnwrappedTransaction(transaction)
    } map (_.reverse)
  }

  private def updateIndexes(content: ContentStatic, incompleteTransactions: Seq[UnwrappedTransaction]): Future[Unit] = {
    if (ContentLogic.isCollectionUri(content.documentUri)) {
      val isCollectionDelete = incompleteTransactions.exists { it ⇒
        it.transaction.itemId.isEmpty && it.unwrappedBody.headers.method == Method.FEED_DELETE
      }
      if (isCollectionDelete) {
        // todo: cache index meta
        db.selectIndexDefs(content.documentUri).flatMap { indexDefsIterator ⇒
          val indexDefs = indexDefsIterator.toSeq
          FutureUtils.serial(indexDefs) { indexDef ⇒
            log.debug(s"Removing index $indexDef")
            deleteIndexDefAndData(indexDef)
          } map (_ ⇒ {})
        }
      }
      else {
        // todo: refactor, this is crazy
        val itemIds = incompleteTransactions.collect {
          case it if it.transaction.itemId.nonEmpty ⇒
            import com.hypertino.binders.json.JsonBinders._
            val obsoleteMap = it.transaction.obsoleteIndexItems.map(_.parseJson[Map[String, Map[String, Value]]])
            val obsoleteSeq = obsoleteMap.map(_.map(kv ⇒ kv._1 → kv._2.toSeq).toSeq).getOrElse(Seq.empty)
            it.transaction.itemId → obsoleteSeq
        }.groupBy(_._1).mapValues(_.map(_._2)).map(kv ⇒ kv._1 → kv._2.flatten)

        // todo: cache index meta
        db.selectIndexDefs(content.documentUri).flatMap { indexDefsIterator ⇒
          val indexDefs = indexDefsIterator.toSeq
          FutureUtils.serial(itemIds.keys.toSeq) { itemId ⇒
            log.debug(s"Looking for content $itemId")

            // todo: cache content
            db.selectContent(content.documentUri, itemId) flatMap { contentOption ⇒
              if (log.isDebugEnabled) {
                log.debug(s"Indexing content $itemId / $contentOption for $indexDefs")
              }
              FutureUtils.serial(indexDefs) { indexDef ⇒

                // todo: refactor, this is crazy
                val seq: Seq[Seq[(String,Value)]] = itemIds(itemId).filter(_._1 == indexDef.indexId).map(_._2)
                val deleteObsoleteFuture = FutureUtils.serial(seq) { s ⇒
                  db.deleteIndexItem(indexDef.tableName, indexDef.documentUri, indexDef.indexId, itemId, s)
                }

                contentOption match {
                  case Some(item) if !item.isDeleted ⇒
                    deleteObsoleteFuture.flatMap(_ ⇒ indexItem(indexDef, item))

                  case _ ⇒
                    deleteObsoleteFuture.flatMap { _ ⇒
                      val revision = incompleteTransactions.map(t ⇒ t.transaction.revision).max
                      db.updateIndexRevision(indexDef.tableName, indexDef.documentUri, indexDef.indexId, revision)
                    }
                }
              }
            }
          } map (_ ⇒ {})
        }
      }
    } else {
      Future.successful()
    }
  }
}

case class UnwrappedTransaction(transaction: Transaction, unwrappedBody: DynamicRequest)

object UnwrappedTransaction {
  def apply(transaction: Transaction): UnwrappedTransaction = UnwrappedTransaction(
    transaction, MessageReader.fromString(transaction.body, DynamicRequest.apply)
  )
}
