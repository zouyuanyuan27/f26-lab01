package edu.cmu.cs214.booking.service;

import edu.cmu.cs214.booking.domain.Booking;
import edu.cmu.cs214.booking.domain.Room;
import edu.cmu.cs214.booking.domain.TimeInterval;
import edu.cmu.cs214.booking.domain.User;
import edu.cmu.cs214.booking.domain.WaitlistEntry;
import edu.cmu.cs214.booking.repo.BookingStore;
import java.util.List;

/**
 * Coordinates bookings and the waitlist. Enforces the core invariant: a room
 * never holds two confirmed bookings whose intervals overlap. Persistence is
 * delegated to a {@link BookingStore}.
 */
public class BookingService {

    private final BookingStore store;
    private int nextBookingSeq = 1;
    private int nextWaitlistSeq = 1;

    public BookingService(BookingStore store) {
        this.store = store;
    }

    /**
     * Attempts to book {@code room} for {@code user} over {@code interval}. If the
     * room is free over that interval the booking is confirmed; otherwise the user
     * is placed on the room's waitlist.
     */
    public BookingResult book(Room room, User user, TimeInterval interval) {
        for (Booking existing : store.bookingsForRoom(room)) {
            if (existing.interval().overlaps(interval)) {
                int position = store.waitlistForRoom(room).size() + 1;
                int seq = nextWaitlistSeq++;
                store.addWaitlistEntry(new WaitlistEntry("w" + seq, room, user, interval, seq));
                return new BookingResult.Waitlisted(position);
            }
        }
        Booking booking = new Booking("b" + nextBookingSeq++, room, user, interval);
        store.addBooking(booking);
        return new BookingResult.Confirmed(booking);
    }

    /** Returns the confirmed bookings for {@code room}. */
    public List<Booking> listBookings(Room room) {
        return store.bookingsForRoom(room);
    }

    /**
     * Cancels the confirmed booking with the given id, freeing its slot.
     * If no booking has that id, does nothing: no exception, no promotion.
     *
     * <p>After removal, promotes from the cancelled booking's room's waitlist:
     * waiters are considered first-come-first-served (by {@code seq}); the first
     * waiter whose interval does not overlap any remaining confirmed booking of
     * that room is promoted into a confirmed booking and their waitlist entry is
     * removed. At most one waiter is promoted per cancellation, and promotion
     * never creates an overlap: if a waiter still conflicts, they are skipped.
     */
    public void cancelBooking(String bookingId) {
        var toCancel = store.findBooking(bookingId);
        if (toCancel.isEmpty()) {
            return; // unknown id → no-op
        }
        Room room = toCancel.get().room();
        store.removeBooking(bookingId);

        // Promote at most one waiter, FCFS by seq, skipping any who still conflict.
        List<WaitlistEntry> waitlist = new java.util.ArrayList<>(store.waitlistForRoom(room));
        waitlist.sort(java.util.Comparator.comparingInt(WaitlistEntry::seq));
        for (WaitlistEntry waiter : waitlist) {
            if (!conflictsWithAnyBooking(room, waiter.interval())) {
                store.removeWaitlistEntry(waiter.id());
                store.addBooking(new Booking("b" + nextBookingSeq++, room, waiter.user(), waiter.interval()));
                return;
            }
        }
        // No waiter fits → promote no one.
    }

    /** True iff {@code interval} overlaps any remaining confirmed booking of {@code room}. */
    private boolean conflictsWithAnyBooking(Room room, TimeInterval interval) {
        for (Booking existing : store.bookingsForRoom(room)) {
            if (existing.interval().overlaps(interval)) {
                return true;
            }
        }
        return false;
    }
}
