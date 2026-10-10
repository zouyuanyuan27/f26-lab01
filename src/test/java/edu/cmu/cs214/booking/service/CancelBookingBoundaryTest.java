package edu.cmu.cs214.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.booking.domain.Booking;
import edu.cmu.cs214.booking.domain.Room;
import edu.cmu.cs214.booking.domain.TimeInterval;
import edu.cmu.cs214.booking.domain.User;
import edu.cmu.cs214.booking.repo.InMemoryBookingStore;
import org.junit.jupiter.api.Test;

/**
 * Milestone 2, part 2 (member C): boundary tests S4-S6 for {@code cancelBooking}.
 *
 * <p>S4: promotion is first-come-first-served (by seq) and at most one waiter
 * is promoted per cancellation, even if further waiters would also fit.
 * S5: a waiter who still conflicts with a remaining booking is skipped, and a
 * later waiter who fits is promoted instead — this guards the core invariant
 * that a room never holds two overlapping confirmed bookings.
 * S6: promotion never crosses rooms — a cancellation in one room never
 * promotes a waiter from another room's waitlist.
 */
class CancelBookingBoundaryTest {

    private final Room roomA = new Room("A", "Alpha", 10);
    private final Room roomB = new Room("B", "Beta", 4);
    private final User alice = new User("u1", "Alice");
    private final User bob = new User("u2", "Bob");
    private final User carol = new User("u3", "Carol");
    private final User dave = new User("u4", "Dave");

    private InMemoryBookingStore store;
    private BookingService svc;

    private BookingService newService() {
        store = new InMemoryBookingStore();
        svc = new BookingService(store);
        return svc;
    }

    /**
     * S4 FCFS + at most one promotion: Alice holds [600,720); Bob (seq 2) waits
     * for [600,660) and Carol (seq 3, later) waits for [660,700). After Alice
     * cancels, only Bob — the earlier waiter — is promoted; Carol must stay on
     * the waitlist even though her interval would also fit next to Bob's.
     */
    @Test
    void promotionIsFcfsAndPromotesAtMostOneWaiter() {
        svc = newService();
        BookingResult r = svc.book(roomA, alice, new TimeInterval(600, 720));
        Booking a = assertInstanceOf(BookingResult.Confirmed.class, r).booking();
        assertInstanceOf(BookingResult.Waitlisted.class,
                svc.book(roomA, bob, new TimeInterval(600, 660)));   // seq 2, first waiter
        assertInstanceOf(BookingResult.Waitlisted.class,
                svc.book(roomA, carol, new TimeInterval(660, 700))); // seq 3, later waiter

        svc.cancelBooking(a.id());

        // Exactly one booking remains: bob's, over [600,660)
        var bookings = svc.listBookings(roomA);
        assertEquals(1, bookings.size(),
                "at most one waiter may be promoted per cancellation");
        assertEquals(bob, bookings.get(0).user(),
                "the earlier waiter (FCFS by seq) must be the one promoted");
        assertEquals(new TimeInterval(600, 660), bookings.get(0).interval());

        // Carol stays on the waitlist
        assertEquals(1, store.waitlistForRoom(roomA).size(),
                "the later waiter must remain waitlisted");
        assertEquals(carol, store.waitlistForRoom(roomA).get(0).user(),
                "it must be Carol who is still waitlisted");
    }

    /**
     * S5 skip conflicting waiters: Alice [600,660) and Dave [700,760) are
     * confirmed; Bob (seq 2) waits for [630,730) — which overlaps Dave's
     * [700,760) — and Carol (seq 3) waits for [610,650), which fits next to
     * Dave. After Alice cancels, Bob must be SKIPPED (promoting him would
     * create an overlap with Dave) and Carol must be promoted instead.
     * This test guards the core invariant directly.
     */
    @Test
    void conflictingWaiterIsSkippedAndLaterFittingWaiterIsPromoted() {
        svc = newService();
        assertInstanceOf(BookingResult.Confirmed.class,
                svc.book(roomA, dave, new TimeInterval(700, 760)));  // confirmed first
        BookingResult r = svc.book(roomA, alice, new TimeInterval(600, 660));
        Booking a = assertInstanceOf(BookingResult.Confirmed.class, r).booking();
        assertInstanceOf(BookingResult.Waitlisted.class,
                svc.book(roomA, bob, new TimeInterval(630, 730)));   // seq: conflicts with Dave
        assertInstanceOf(BookingResult.Waitlisted.class,
                svc.book(roomA, carol, new TimeInterval(610, 650))); // seq: fits next to Dave

        svc.cancelBooking(a.id());

        // Remaining confirmed: Dave [700,760) + promoted Carol [610,650) — no overlap
        var bookings = svc.listBookings(roomA);
        assertEquals(2, bookings.size(),
                "Dave keeps his booking and Carol is promoted");
        assertTrue(bookings.stream().anyMatch(b -> b.user().equals(dave)),
                "Dave's booking must remain");
        assertTrue(bookings.stream().anyMatch(b -> b.user().equals(carol)),
                "Carol (later waiter, no conflict) must be promoted");

        // Bob is skipped and stays on the waitlist
        assertEquals(1, store.waitlistForRoom(roomA).size(),
                "the conflicting waiter must be skipped, not promoted");
        assertEquals(bob, store.waitlistForRoom(roomA).get(0).user(),
                "it must be Bob who is still waitlisted");
    }

    /**
     * S6 no cross-room promotion: Alice holds room A; Bob is waitlisted on
     * room B (because Carol holds B). Cancelling Alice's room-A booking must
     * NOT promote Bob — his waitlist entry belongs to room B.
     */
    @Test
    void cancellationInOneRoomNeverPromotesWaitersOfAnotherRoom() {
        svc = newService();
        assertInstanceOf(BookingResult.Confirmed.class,
                svc.book(roomB, carol, new TimeInterval(600, 660))); // B is taken
        assertInstanceOf(BookingResult.Waitlisted.class,
                svc.book(roomB, bob, new TimeInterval(600, 660)));   // Bob waits on room B
        BookingResult r = svc.book(roomA, alice, new TimeInterval(600, 660));
        Booking a = assertInstanceOf(BookingResult.Confirmed.class, r).booking();

        svc.cancelBooking(a.id());

        // Room A is simply empty; Bob must still be waitlisted on room B
        assertEquals(0, svc.listBookings(roomA).size(),
                "room A should hold no bookings after cancellation");
        assertEquals(1, store.waitlistForRoom(roomB).size(),
                "a cancellation in room A must not promote room B's waiters");
        assertEquals(bob, store.waitlistForRoom(roomB).get(0).user(),
                "Bob must still be waitlisted on room B");
        assertEquals(1, svc.listBookings(roomB).size(),
                "room B's confirmed booking (Carol) must be untouched");
    }
}
