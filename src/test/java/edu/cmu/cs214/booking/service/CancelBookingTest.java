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
 * Milestone 2 tests for {@code cancelBooking}.
 * S1/S2 cover the basic removal and unknown-id behaviour; S3 covers promotion.
 */
class CancelBookingTest {

    private final Room roomA = new Room("A", "Alpha", 10);
    private final User alice = new User("u1", "Alice");
    private final User bob = new User("u2", "Bob");

    private InMemoryBookingStore store;
    private BookingService svc;

    private BookingService newService() {
        store = new InMemoryBookingStore();
        svc = new BookingService(store);
        return svc;
    }

    /** S1 basic cancel: the confirmed booking is removed, its slot freed. */
    @Test
    void cancellingRemovesTheBookingAndFreesTheSlot() {
        svc = newService();
        BookingResult r = svc.book(roomA, alice, new TimeInterval(600, 660));
        Booking a = assertInstanceOf(BookingResult.Confirmed.class, r).booking();

        svc.cancelBooking(a.id());

        assertEquals(0, svc.listBookings(roomA).size(),
                "room A should hold no bookings after cancellation");
    }

    /** S2 unknown id: nothing happens — no exception, no promotion. */
    @Test
    void cancellingAnUnknownIdDoesNothing() {
        svc = newService();
        BookingResult r = svc.book(roomA, alice, new TimeInterval(600, 660));
        Booking a = assertInstanceOf(BookingResult.Confirmed.class, r).booking();
        assertInstanceOf(BookingResult.Waitlisted.class,
                svc.book(roomA, bob, new TimeInterval(630, 700)));

        svc.cancelBooking("no-such-id"); // must not throw, must not promote

        assertTrue(store.findBooking(a.id()).isPresent(),
                "the existing booking must survive");
        assertEquals(1, svc.listBookings(roomA).size());
        assertEquals(1, store.waitlistForRoom(roomA).size(),
                "no waiter may be promoted by an unknown id");
    }

    /**
     * S3 promotion: after the cancellation, the waitlisted user holds a
     * confirmed booking and their waitlist entry is gone. This test fails
     * on a "delete-only" implementation of cancelBooking.
     */
    @Test
    void cancellingPromotesTheWaitlistedUser() {
        svc = newService();
        BookingResult r = svc.book(roomA, alice, new TimeInterval(600, 660));
        Booking a = assertInstanceOf(BookingResult.Confirmed.class, r).booking();
        assertInstanceOf(BookingResult.Waitlisted.class,
                svc.book(roomA, bob, new TimeInterval(630, 700)));

        svc.cancelBooking(a.id());

        // roomA now holds exactly one booking: bob's, over [630,700)
        var bookings = svc.listBookings(roomA);
        assertEquals(1, bookings.size(),
                "the waitlisted user should now hold exactly one confirmed booking");
        Booking promoted = bookings.get(0);
        assertEquals(bob, promoted.user());
        assertEquals(new TimeInterval(630, 700), promoted.interval());

        // bob is no longer on roomA's waitlist
        assertEquals(0, store.waitlistForRoom(roomA).size(),
                "the promoted waiter's entry must be removed");
    }
}
