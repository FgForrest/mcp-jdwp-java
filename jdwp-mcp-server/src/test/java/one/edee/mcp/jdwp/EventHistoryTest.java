package one.edee.mcp.jdwp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EventHistory}, the bounded ring buffer that backs {@code jdwp_get_events}.
 * These tests are pure — no JDI involvement — and exercise insertion, eviction at the size limit,
 * recent-N retrieval ordering, and clearing semantics.
 */
class EventHistoryTest {

	private static final int MAX_EVENTS = 500;

	@Test
	@DisplayName("recording an event makes it retrievable via getRecent")
	void shouldRecordAndRetrieveEvent() {
		EventHistory history = new EventHistory();

		history.record(new EventHistory.DebugEvent("BREAKPOINT", "hit at Foo:42"));

		List<EventHistory.DebugEvent> recent = history.getRecent(10);
		assertThat(recent).hasSize(1);
		assertThat(recent.get(0).type()).isEqualTo("BREAKPOINT");
		assertThat(recent.get(0).summary()).isEqualTo("hit at Foo:42");
	}

	@Test
	@DisplayName("getRecent returns events in insertion order")
	void shouldReturnEventsInInsertionOrder() {
		EventHistory history = new EventHistory();
		history.record(new EventHistory.DebugEvent("BREAKPOINT", "first"));
		history.record(new EventHistory.DebugEvent("STEP", "second"));
		history.record(new EventHistory.DebugEvent("EXCEPTION", "third"));

		List<EventHistory.DebugEvent> recent = history.getRecent(10);

		assertThat(recent).extracting(EventHistory.DebugEvent::summary)
			.containsExactly("first", "second", "third");
	}

	@Test
	@DisplayName("getRecent honours the count parameter and returns the latest N")
	void shouldReturnLatestNEvents() {
		EventHistory history = new EventHistory();
		for (int i = 0; i < 10; i++) {
			history.record(new EventHistory.DebugEvent("BREAKPOINT", "event-" + i));
		}

		List<EventHistory.DebugEvent> recent = history.getRecent(3);

		assertThat(recent).extracting(EventHistory.DebugEvent::summary)
			.containsExactly("event-7", "event-8", "event-9");
	}

	@Test
	@DisplayName("ring buffer evicts oldest entries beyond the MAX_EVENTS cap")
	void shouldEvictOldestWhenExceedingMaxSize() {
		EventHistory history = new EventHistory();
		for (int i = 0; i < MAX_EVENTS + 10; i++) {
			history.record(new EventHistory.DebugEvent("BREAKPOINT", "event-" + i));
		}

		assertThat(history.size()).isEqualTo(MAX_EVENTS);

		List<EventHistory.DebugEvent> recent = history.getRecent(MAX_EVENTS);
		assertThat(recent).hasSize(MAX_EVENTS);
		// First retained entry should be the 10th (events 0..9 were evicted)
		assertThat(recent.get(0).summary()).isEqualTo("event-10");
		assertThat(recent.get(MAX_EVENTS - 1).summary()).isEqualTo("event-" + (MAX_EVENTS + 9));
	}

	@Test
	@DisplayName("clear empties the buffer")
	void shouldClear() {
		EventHistory history = new EventHistory();
		history.record(new EventHistory.DebugEvent("STEP", "x"));
		history.record(new EventHistory.DebugEvent("STEP", "y"));
		assertThat(history.size()).isEqualTo(2);

		history.clear();

		assertThat(history.size()).isZero();
		assertThat(history.getRecent(10)).isEmpty();
	}

	@Test
	@DisplayName("getRecent on an empty history returns an empty list")
	void shouldReturnEmptyListOnEmptyHistory() {
		EventHistory history = new EventHistory();
		assertThat(history.getRecent(20)).isEmpty();
	}

	@Test
	@DisplayName("getRecent caps to actual size when count exceeds buffer")
	void shouldClampCountToBufferSize() {
		EventHistory history = new EventHistory();
		history.record(new EventHistory.DebugEvent("BREAKPOINT", "only"));

		assertThat(history.getRecent(100)).hasSize(1);
	}

	@Test
	@DisplayName("DebugEvent two-arg constructor sets timestamp and empty details")
	void shouldDefaultDetailsToEmptyMap() {
		EventHistory.DebugEvent event = new EventHistory.DebugEvent("LOGPOINT", "x = 5");

		assertThat(event.type()).isEqualTo("LOGPOINT");
		assertThat(event.summary()).isEqualTo("x = 5");
		assertThat(event.details()).isEmpty();
		assertThat(event.timestamp()).isNotNull();
	}

	@Test
	@DisplayName("DebugEvent three-arg constructor preserves the details map")
	void shouldPreserveDetailsMap() {
		Map<String, String> details = Map.of("breakpointId", "5", "thread", "main");
		EventHistory.DebugEvent event = new EventHistory.DebugEvent("BREAKPOINT", "hit", details);

		assertThat(event.details()).containsExactlyInAnyOrderEntriesOf(details);
	}

	@Test
	@DisplayName("a freshly constructed DebugEvent is UNSTAMPED until recorded")
	void shouldConstructUnstamped() {
		assertThat(new EventHistory.DebugEvent("STEP", "x").sessionEpoch())
			.isEqualTo(EventHistory.DebugEvent.UNSTAMPED);
	}

	@Test
	@DisplayName("record stamps the current session epoch onto the event")
	void shouldStampCurrentEpochOnRecord() {
		EventHistory history = new EventHistory();
		assertThat(history.currentEpoch()).isZero();

		history.record(new EventHistory.DebugEvent("VM_START", "s0 event"));
		assertThat(history.getRecent(1).get(0).sessionEpoch()).isZero();
	}

	@Test
	@DisplayName("beginNewSession increments the epoch and tags only events recorded afterwards")
	void shouldSegmentEventsBySession() {
		EventHistory history = new EventHistory();
		history.record(new EventHistory.DebugEvent("EXCEPTION", "old-vm"));

		assertThat(history.beginNewSession()).isEqualTo(1);
		assertThat(history.currentEpoch()).isEqualTo(1);
		history.record(new EventHistory.DebugEvent("BREAKPOINT", "new-vm"));

		List<EventHistory.DebugEvent> recent = history.getRecent(10);
		assertThat(recent).hasSize(2);
		// the pre-attach event keeps the old epoch; the post-attach event carries the new one
		assertThat(recent.get(0).summary()).isEqualTo("old-vm");
		assertThat(recent.get(0).sessionEpoch()).isZero();
		assertThat(recent.get(1).summary()).isEqualTo("new-vm");
		assertThat(recent.get(1).sessionEpoch()).isEqualTo(1);
	}

	@Test
	@DisplayName("an already-stamped event is not re-stamped on record")
	void shouldNotRestampAlreadyStampedEvent() {
		EventHistory history = new EventHistory();
		history.beginNewSession(); // currentEpoch = 1
		EventHistory.DebugEvent preStamped =
			new EventHistory.DebugEvent("BREAKPOINT", "explicit").withEpoch(7);

		history.record(preStamped);

		assertThat(history.getRecent(1).get(0).sessionEpoch()).isEqualTo(7);
	}

	@Test
	@DisplayName("counts logpoint-type entries until marked read; other types are not counted (#50)")
	void countsUnreadLogpointEntries() {
		final EventHistory history = new EventHistory();
		history.record(new EventHistory.DebugEvent("FIELD_LOGPOINT", "a"));
		history.record(new EventHistory.DebugEvent("FIELD_LOGPOINT", "b"));
		history.record(new EventHistory.DebugEvent("LOGPOINT", "c"));
		history.record(new EventHistory.DebugEvent("BREAKPOINT", "d"));

		assertThat(history.unreadSummary()).isEqualTo("2 FIELD_LOGPOINT, 1 LOGPOINT");
		history.markRead();
		assertThat(history.unreadSummary()).isEmpty();
	}
}
