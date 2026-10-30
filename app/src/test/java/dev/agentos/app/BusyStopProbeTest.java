package dev.agentos.app;

import dev.agentos.core.RunControl;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BusyStopProbeTest {
    @Test public void inactiveWrongOwnerAndLateCallsCannotContaminateTheNextSession() throws Exception {
        RunControl first = new RunControl(), other = new RunControl();
        BusyStopProbe.Call inactive = BusyStopProbe.entering(first, first.getExecutionEpoch());
        BusyStopProbe.Call late;
        try (BusyStopProbe.Session original = BusyStopProbe.start(first);
             RunControl.DispatchTicket ticket = first.beginDispatch(first.getExecutionEpoch())) {
            late = BusyStopProbe.entering(first, ticket.executionEpoch);
            assertEquals(1, original.snapshot().length);
        }
        try (BusyStopProbe.Session next = BusyStopProbe.start(other)) {
            inactive.returned(true);
            late.returned(true);
            late.threw();
            late.settled(first);
            BusyStopProbe.entering(first, first.getExecutionEpoch()).returned(true);
            BusyStopProbe.stopPoint(first, BusyStopProbe.CANCEL_RETURN);
            assertEquals(0, next.snapshot().length);
            assertEquals(0, next.overflow());
        }
    }

    @Test public void realTicketAndCancellationMetadataArePreservedWithoutSettlingForTheWorker() throws Exception {
        RunControl owner = new RunControl();
        long epoch = owner.getExecutionEpoch();
        try (BusyStopProbe.Session session = BusyStopProbe.start(owner)) {
            RunControl.DispatchTicket ticket = owner.beginDispatch(epoch);
            BusyStopProbe.Call call = BusyStopProbe.entering(owner, epoch);
            owner.cancel();
            BusyStopProbe.stopPoint(owner, BusyStopProbe.CANCEL_RETURN);
            assertTrue(owner.hasInFlightDispatch());
            call.returned(false);
            call.settled(owner);
            assertTrue("Diagnostics must not settle the original ticket", owner.hasInFlightDispatch());
            ticket.close();
            call.settled(owner);
            long[][] values = session.snapshot();
            assertArrayEquals(new long[]{BusyStopProbe.ENTER, epoch, 1, 0}, metadata(values[0]));
            assertArrayEquals(new long[]{BusyStopProbe.CANCEL_RETURN, epoch + 1, 1, 1}, metadata(values[1]));
            assertArrayEquals(new long[]{BusyStopProbe.RETURN, epoch, 1, 0}, metadata(values[2]));
            assertEquals(0, values[3][4]);
            assertEquals(1, values[4][4]);
            for (int i = 1; i < values.length; i++) assertTrue(values[i][1] >= values[i - 1][1]);
        }
    }

    @Test public void overflowKeepsTheOriginalPrefixAndExceptionObservationDoesNotManufactureReturn() throws Exception {
        RunControl owner = new RunControl();
        try (BusyStopProbe.Session session = BusyStopProbe.start(owner);
             RunControl.DispatchTicket ticket = owner.beginDispatch(owner.getExecutionEpoch())) {
            BusyStopProbe.Call call = BusyStopProbe.entering(owner, ticket.executionEpoch);
            call.threw();
            for (int i = 0; i < BusyStopProbe.CAPACITY + 3; i++) BusyStopProbe.stopPoint(owner, BusyStopProbe.STOP_OWNED_ENTRY);
            long[][] values = session.snapshot();
            assertEquals(BusyStopProbe.CAPACITY, values.length);
            assertEquals(5, session.overflow());
            assertEquals(BusyStopProbe.ENTER, values[0][0]);
            assertEquals(BusyStopProbe.THROW, values[1][0]);
            for (long[] row : values) assertNotEquals(BusyStopProbe.RETURN, row[0]);
            values[0][0] = 99;
            assertEquals("Snapshots must not mutate stored evidence", BusyStopProbe.ENTER, session.snapshot()[0][0]);
        }
    }
    private static long[] metadata(long[] row) { return new long[]{row[0], row[2], row[3], row[4]}; }
}
