/*
 * Copyright (C) 2020-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.eventsourced.scaladsl

import java.time.Instant

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.persistence.query.NoOffset
import akka.persistence.query.Offset
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.persistence.query.scaladsl.ReadJournal
import akka.persistence.query.typed.scaladsl.EventTimestampQuery
import akka.persistence.query.typed.scaladsl.EventsBySliceQuery
import akka.persistence.query.typed.scaladsl.EventsBySliceStartingFromSnapshotsQuery
import akka.persistence.query.typed.scaladsl.LoadEventQuery
import akka.projection.BySlicesSourceProvider
import akka.projection.eventsourced.EventEnvelope
import akka.projection.internal.CanTriggerReplay
import akka.projection.scaladsl.SourceProvider
import akka.stream.scaladsl.Source

object EventSourcedProvider {

  def eventsByTag[Event](
      system: ActorSystem[_],
      readJournalPluginId: String,
      tag: String): SourceProvider[Offset, EventEnvelope[Event]] = {
    val eventsByTagQuery =
      PersistenceQuery(system).readJournalFor[EventsByTagQuery](readJournalPluginId)
    eventsByTag(system, eventsByTagQuery, tag)
  }

  def eventsByTag[Event](
      system: ActorSystem[_],
      eventsByTagQuery: EventsByTagQuery,
      tag: String): SourceProvider[Offset, EventEnvelope[Event]] = {
    new EventsByTagSourceProvider(eventsByTagQuery, tag, system)
  }

  private class EventsByTagSourceProvider[Event](
      eventsByTagQuery: EventsByTagQuery,
      tag: String,
      system: ActorSystem[_])
      extends SourceProvider[Offset, EventEnvelope[Event]] {
    implicit val executionContext: ExecutionContext = system.executionContext

    override def source(offset: () => Future[Option[Offset]]): Future[Source[EventEnvelope[Event], NotUsed]] =
      offset().map { offsetOpt =>
        val offset = offsetOpt.getOrElse(NoOffset)
        eventsByTagQuery
          .eventsByTag(tag, offset)
          .map(env => EventEnvelope(env))
      }

    override def extractOffset(envelope: EventEnvelope[Event]): Offset = envelope.offset

    override def extractCreationTime(envelope: EventEnvelope[Event]): Long = envelope.timestamp
  }

  def eventsBySlices[Event](
      system: ActorSystem[_],
      readJournalPluginId: String,
      entityType: String,
      minSlice: Int,
      maxSlice: Int): SourceProvider[Offset, akka.persistence.query.typed.EventEnvelope[Event]] = {
    val eventsBySlicesQuery =
      PersistenceQuery(system).readJournalFor[EventsBySliceQuery](readJournalPluginId)
    eventsBySlices(system, eventsBySlicesQuery, entityType, minSlice, maxSlice)
  }

  def eventsBySlices[Event](
      system: ActorSystem[_],
      eventsBySlicesQuery: EventsBySliceQuery,
      entityType: String,
      minSlice: Int,
      maxSlice: Int): SourceProvider[Offset, akka.persistence.query.typed.EventEnvelope[Event]] = {
    eventsBySlicesQuery match {
      case query: EventsBySliceQuery with CanTriggerReplay =>
        new EventsBySlicesSourceProvider[Event](eventsBySlicesQuery, entityType, minSlice, maxSlice, system)
          with CanTriggerReplay {
          override private[akka] def triggerReplay(persistenceId: String, fromSeqNr: Long): Unit =
            query.triggerReplay(persistenceId, fromSeqNr)
        }
      case _ =>
        new EventsBySlicesSourceProvider(eventsBySlicesQuery, entityType, minSlice, maxSlice, system)
    }
  }

  def eventsBySlicesStartingFromSnapshots[Snapshot, Event](
      system: ActorSystem[_],
      readJournalPluginId: String,
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      transformSnapshot: Snapshot => Event)
      : SourceProvider[Offset, akka.persistence.query.typed.EventEnvelope[Event]] = {
    val eventsBySlicesQuery =
      PersistenceQuery(system).readJournalFor[EventsBySliceStartingFromSnapshotsQuery](readJournalPluginId)
    eventsBySlicesStartingFromSnapshots(system, eventsBySlicesQuery, entityType, minSlice, maxSlice, transformSnapshot)
  }

  def eventsBySlicesStartingFromSnapshots[Snapshot, Event](
      system: ActorSystem[_],
      eventsBySlicesQuery: EventsBySliceStartingFromSnapshotsQuery,
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      transformSnapshot: Snapshot => Event)
      : SourceProvider[Offset, akka.persistence.query.typed.EventEnvelope[Event]] = {
    eventsBySlicesQuery match {
      case query: EventsBySliceStartingFromSnapshotsQuery with CanTriggerReplay =>
        new EventsBySlicesStartingFromSnapshotsSourceProvider[Snapshot, Event](
          eventsBySlicesQuery,
          entityType,
          minSlice,
          maxSlice,
          transformSnapshot,
          system) with CanTriggerReplay {
          override private[akka] def triggerReplay(persistenceId: String, fromSeqNr: Long): Unit =
            query.triggerReplay(persistenceId, fromSeqNr)
        }
      case _ =>
        new EventsBySlicesStartingFromSnapshotsSourceProvider(
          eventsBySlicesQuery,
          entityType,
          minSlice,
          maxSlice,
          transformSnapshot,
          system)
    }
  }

  def sliceForPersistenceId(system: ActorSystem[_], readJournalPluginId: String, persistenceId: String): Int =
    PersistenceQuery(system)
      .readJournalFor[EventsBySliceQuery](readJournalPluginId)
      .sliceForPersistenceId(persistenceId)

  def sliceRanges(system: ActorSystem[_], readJournalPluginId: String, numberOfRanges: Int): immutable.Seq[Range] =
    PersistenceQuery(system).readJournalFor[EventsBySliceQuery](readJournalPluginId).sliceRanges(numberOfRanges)

  private class EventsBySlicesSourceProvider[Event](
      eventsBySlicesQuery: EventsBySliceQuery,
      entityType: String,
      override val minSlice: Int,
      override val maxSlice: Int,
      system: ActorSystem[_])
      extends SourceProvider[Offset, akka.persistence.query.typed.EventEnvelope[Event]]
      with BySlicesSourceProvider
      with EventTimestampQuerySourceProvider
      with LoadEventQuerySourceProvider {
    implicit val executionContext: ExecutionContext = system.executionContext

    override def readJournal: ReadJournal = eventsBySlicesQuery

    override def source(offset: () => Future[Option[Offset]])
        : Future[Source[akka.persistence.query.typed.EventEnvelope[Event], NotUsed]] =
      offset().map { offsetOpt =>
        val offset = offsetOpt.getOrElse(NoOffset)
        eventsBySlicesQuery.eventsBySlices(entityType, minSlice, maxSlice, offset)
      }

    override def extractOffset(envelope: akka.persistence.query.typed.EventEnvelope[Event]): Offset = envelope.offset

    override def extractCreationTime(envelope: akka.persistence.query.typed.EventEnvelope[Event]): Long =
      envelope.timestamp

  }

  private class EventsBySlicesStartingFromSnapshotsSourceProvider[Snapshot, Event](
      eventsBySlicesQuery: EventsBySliceStartingFromSnapshotsQuery,
      entityType: String,
      override val minSlice: Int,
      override val maxSlice: Int,
      transformSnapshot: Snapshot => Event,
      system: ActorSystem[_])
      extends SourceProvider[Offset, akka.persistence.query.typed.EventEnvelope[Event]]
      with BySlicesSourceProvider
      with EventTimestampQuerySourceProvider
      with LoadEventQuerySourceProvider {
    implicit val executionContext: ExecutionContext = system.executionContext

    override def readJournal: ReadJournal = eventsBySlicesQuery

    override def source(offset: () => Future[Option[Offset]])
        : Future[Source[akka.persistence.query.typed.EventEnvelope[Event], NotUsed]] =
      offset().map { offsetOpt =>
        val offset = offsetOpt.getOrElse(NoOffset)
        eventsBySlicesQuery.eventsBySlicesStartingFromSnapshots(
          entityType,
          minSlice,
          maxSlice,
          offset,
          transformSnapshot)
      }

    override def extractOffset(envelope: akka.persistence.query.typed.EventEnvelope[Event]): Offset = envelope.offset

    override def extractCreationTime(envelope: akka.persistence.query.typed.EventEnvelope[Event]): Long =
      envelope.timestamp

  }

  private trait EventTimestampQuerySourceProvider extends EventTimestampQuery {
    def readJournal: ReadJournal

    override def timestampOf(persistenceId: String, sequenceNr: Long): Future[Option[Instant]] =
      readJournal match {
        case timestampQuery: EventTimestampQuery =>
          timestampQuery.timestampOf(persistenceId, sequenceNr)
        case _ =>
          Future.failed(
            new IllegalStateException(
              s"[${readJournal.getClass.getName}] must implement [${classOf[EventTimestampQuery].getName}]"))
      }
  }

  private trait LoadEventQuerySourceProvider extends LoadEventQuery {
    def readJournal: ReadJournal

    override def loadEnvelope[Evt](
        persistenceId: String,
        sequenceNr: Long): Future[akka.persistence.query.typed.EventEnvelope[Evt]] =
      readJournal match {
        case laodEventQuery: LoadEventQuery =>
          laodEventQuery.loadEnvelope(persistenceId, sequenceNr)
        case _ =>
          Future.failed(
            new IllegalStateException(
              s"[${readJournal.getClass.getName}] must implement [${classOf[LoadEventQuery].getName}]"))
      }
  }

}
