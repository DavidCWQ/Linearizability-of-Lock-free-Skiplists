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

            // Sort the log based on the timestamp to order operations chronologically.
            Arrays.sort(log, Comparator.comparingLong(entry -> entry.timestamp));

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
