import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Comparator;

public class Log {
        private Log() {
                // Do not implement
        }

        public static int[] validate(Log.Entry[] log) {

            if (log == null) return new int[]{0,1};

            // Initialization.
            int discrepancyCount = 0;
            HashSet<Integer> referenceSet = new HashSet<>();

            // Filter out entries with the method EMPTY
            log = removeEmptyEntries(log);

            // Sort the log based on the timestamp to order operations chronologically.
            Arrays.sort(log, Comparator.comparingLong(entry -> entry.timestamp));

            // Apply the FAKE_REMOVE filter to adjust the method and timestamps.
            adjustFakeRemove(log);

            // Iterate through each log entry and replay the operations on the HashSet.
            for (Log.Entry entry : log) {
                boolean resultFromSet = switch (entry.method) {
                    case ADD -> referenceSet.add(entry.arg);
                    case REMOVE -> referenceSet.remove(entry.arg);
                    case CONTAINS -> referenceSet.contains(entry.arg);
                    default -> throw new RuntimeException("Error: Invalid entry.method in HashSet.");
                };

                // Check if the result from the set matches the expected result from the log entry.
                if (resultFromSet != entry.ret) {
                    discrepancyCount++;
                }
            }

            // Return the total number of discrepancies found & total logCounts
            return new int[]{discrepancyCount, log.length};
        }

        // Function to remove entries with method EMPTY
        private static Log.Entry[] removeEmptyEntries(Log.Entry[] log) {
            ArrayList<Log.Entry> filteredLog = new ArrayList<>();
            for (Log.Entry entry : log) {
                if (entry.method != Method.EMPTY) {
                    filteredLog.add(entry);
                }
            }
            return filteredLog.toArray(new Log.Entry[0]);
        }

        // Function to change FAKE_REMOVE and adjust their timestamps.
        private static void adjustFakeRemove(Log.Entry[] log) {
            long lastRemoveTimestamp = Long.MIN_VALUE; // Track the last REMOVE timestamp

            for (Log.Entry entry : log) {
                if (entry.method == Method.REMOVE) {
                    // Update the last REMOVE timestamp.
                    lastRemoveTimestamp = entry.timestamp;
                } else if (entry.method == Method.FAKE_REMOVE) {
                    // Change FAKE_REMOVE to REMOVE.
                    entry.method = Method.REMOVE;

                    // Set timestamp to 1 nanosecond after the last REMOVE timestamp.
                    if (lastRemoveTimestamp != Long.MIN_VALUE) {
                        entry.timestamp = lastRemoveTimestamp + 1;
                    }
                }
            }
        }

        // Log entry for linearization point.
        public static class Entry {
                public Method method;
                public int arg;
                public boolean ret;
                public long timestamp;
                public Entry(Method method, int arg, boolean ret, long timestamp) {
                        this.method = method;
                        this.arg = arg;
                        this.ret = ret;
                        this.timestamp = timestamp;
                }
        }

        public static enum Method {
                ADD, REMOVE, CONTAINS, EMPTY, FAKE_REMOVE
        }
}
