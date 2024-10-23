import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicMarkableReference;

public class LockFreeSkipListLocalLog<T extends Comparable<T>> implements LockFreeSet<T> {
        /* Number of levels */
        private static final int MAX_LEVEL = 16;
        private final Node<T> head = new Node<T>();
        private final Node<T> tail = new Node<T>();
        private final ConcurrentHashMap<Long, List<Log.Entry>> threadLogs = new ConcurrentHashMap<>();

        public LockFreeSkipListLocalLog() {
                for (int i = 0; i < head.next.length; i++) {
                        head.next[i] = new AtomicMarkableReference<LockFreeSkipListLocalLog.Node<T>>(tail, false);
                }
        }

        private static final class Node<T> {
                private final T value;
                private final AtomicMarkableReference<Node<T>>[] next;
                private final int topLevel;

                @SuppressWarnings("unchecked")
                public Node() {
                        value = null;
                        next = (AtomicMarkableReference<Node<T>>[])new AtomicMarkableReference[MAX_LEVEL + 1];
                        for (int i = 0; i < next.length; i++) {
                                next[i] = new AtomicMarkableReference<Node<T>>(null, false);
                        }
                        topLevel = MAX_LEVEL;
                }

                @SuppressWarnings("unchecked")
                public Node(T x, int height) {
                        value = x;
                        next = (AtomicMarkableReference<Node<T>>[])new AtomicMarkableReference[height + 1];
                        for (int i = 0; i < next.length; i++) {
                                next[i] = new AtomicMarkableReference<Node<T>>(null, false);
                        }
                        topLevel = height;
                }
        }

        /* Returns a level between 0 to MAX_LEVEL,
         * P[randomLevel() = x] = 1/2^(x+1), for x < MAX_LEVEL.
         */
        private static int randomLevel() {
                int r = ThreadLocalRandom.current().nextInt();
                int level = 0;
                r &= (1 << MAX_LEVEL) - 1;
                while ((r & 1) != 0) {
                        r >>>= 1;
                        level++;
                }
                return level;
        }

        @SuppressWarnings("unchecked")
        public boolean add(int threadId, T x) {
                int topLevel = randomLevel();
                int bottomLevel = 0;
                Node<T>[] preds = (Node<T>[]) new Node[MAX_LEVEL + 1];
                Node<T>[] succs = (Node<T>[]) new Node[MAX_LEVEL + 1];
                while (true) {
                        // TODO: Log around the unsuccessful linearization point of add in `find()`
                        boolean found = find(x, preds, succs, Log.Method.ADD);
                        if (found) {
                                return false;
                        } else {
                                Node<T> newNode = new Node(x, topLevel);
                                for (int level = bottomLevel; level <= topLevel; level++) {
                                        Node<T> succ = succs[level];
                                        newNode.next[level].set(succ, false);
                                }
                                Node<T> pred = preds[bottomLevel];
                                Node<T> succ = succs[bottomLevel];
                                // TODO: Log around the successful linearization point of add
                                if (!pred.next[bottomLevel].compareAndSet(succ, newNode, false, false)) {
                                        continue;
                                } else {
                                        logAction(Log.Method.ADD, x.hashCode(),true, -1);
                                }
                                for (int level = bottomLevel + 1; level <= topLevel; level++) {
                                        while (true) {
                                                pred = preds[level];
                                                succ = succs[level];
                                                if (pred.next[level].compareAndSet(succ, newNode, false, false))
                                                        break;
                                                find(x, preds, succs, Log.Method.EMPTY);
                                        }
                                }
                                return true;
                        }
                }
        }

        @SuppressWarnings("unchecked")
        public boolean remove(int threadId, T x) {
                int bottomLevel = 0;
                Node<T>[] preds = (Node<T>[]) new Node[MAX_LEVEL + 1];
                Node<T>[] succs = (Node<T>[]) new Node[MAX_LEVEL + 1];
                Node<T> succ;
                while (true) {
                        // TODO: Log around the unsuccessful linearization point of remove in `find()`
                        boolean found = find(x, preds, succs, Log.Method.REMOVE);
                        if (!found) {
                                return false;
                        } else {
                                Node<T> nodeToRemove = succs[bottomLevel];
                                for (int level = nodeToRemove.topLevel; level >= bottomLevel+1; level --) {
                                        boolean[] marked = {false};
                                        succ = nodeToRemove.next[level].get(marked);
                                        while (!marked[0]) {
                                                nodeToRemove.next[level].compareAndSet(succ, succ, false, true);
                                                succ = nodeToRemove.next[level].get(marked);
                                        }
                                }
                                boolean[] marked = {false};
                                succ = nodeToRemove.next[bottomLevel].get(marked);
                                while (true) {
                                        // TODO: Log around the (un)successful linearization point of remove
                                        boolean iMarkedIt = nodeToRemove.next[bottomLevel].compareAndSet(succ, succ, false, true);
                                        if (iMarkedIt) {
                                                logAction(Log.Method.REMOVE, x.hashCode(), true, -1);
                                        } else if (marked[0]){
                                                logAction(Log.Method.FAKE_REMOVE, x.hashCode(), false, -1);
                                        }
                                        succ = succs[bottomLevel].next[bottomLevel].get(marked);
                                        if (iMarkedIt) {
                                                find(x, preds, succs, Log.Method.EMPTY);
                                                return true;
                                        } else if (marked[0]) {
                                                return false;
                                        }
                                }
                        }
                }
        }

        public boolean contains(int threadId, T x) {
                int bottomLevel = 0;
                int key = x.hashCode();
                boolean[] marked = {false};
                Node<T> pred = head;
                Node<T> curr = null;
                Node<T> succ = null;
                List<Long> time = new ArrayList<>(); // The latest timestamp = LP
                for (int level = MAX_LEVEL; level >= bottomLevel; level--) {
                        // TODO: Log around the linearization point of contains
                        curr = pred.next[level].getReference();
                        time.add(System.nanoTime());
                        while (true) {
                                succ = curr.next[level].get(marked);
                                while (marked[0]) {
                                        // TODO: Log around the linearization point of contains
                                        curr = succ;
                                        time.add(System.nanoTime());
                                        succ = curr.next[level].get(marked);
                                }
                                if (curr.value != null && x.compareTo(curr.value) < 0) {
                                        pred = curr;
                                        curr = succ;
                                } else {
                                        break;
                                }
                        }
                }
                boolean result = curr.value != null && x.compareTo(curr.value) == 0;
                logAction(Log.Method.CONTAINS, x.hashCode(), result, time.get(time.size()-1));
                return result;
        }

        private boolean find(T x, Node<T>[] preds, Node<T>[] succs, Log.Method method) {
                int bottomLevel = 0;
                boolean[] marked = {false};
                boolean snip;
                Node<T> pred = null;
                Node<T> curr = null;
                Node<T> succ = null;
                List<Long> time = new ArrayList<>(); // The latest timestamp = LP
retry:
                while (true) {
                        pred = head;
                        for (int level = MAX_LEVEL; level >= bottomLevel; level--) {
                                // TODO: Log around the unsuccessful LP of add or remove
                                curr = pred.next[level].getReference();
                                time.add(System.nanoTime());
                                while (true) {
                                        succ = curr.next[level].get(marked);
                                        while (marked[0]) {
                                                snip = pred.next[level].compareAndSet(curr, succ, false, false);
                                                if (!snip) continue retry;
                                                // TODO: Log around the unsuccessful LP of add or remove
                                                curr = succ;
                                                time.add(System.nanoTime());
                                                succ = curr.next[level].get(marked);
                                        }
                                        if (curr.value != null && x.compareTo(curr.value) < 0) {
                                                pred = curr;
                                                curr = succ;
                                        } else {
                                                break;
                                        }
                                }

                                preds[level] = pred;
                                succs[level] = curr;
                        }
                        boolean isFound = curr.value != null && x.compareTo(curr.value) == 0;

                        if (isFound && method == Log.Method.ADD)
                                logAction(Log.Method.ADD, x.hashCode(), false, time.get(time.size()-1));
                        else if (!isFound && method == Log.Method.REMOVE)
                                logAction(Log.Method.REMOVE, x.hashCode(), false, time.get(time.size()-1));

                        return isFound;
                }
        }

        public Log.Entry[] getLog() {
                // TODO: This should fetch the log from the skiplist.
                //       Merge all thread-local logs into a global log.
                List<Log.Entry> globalLog = new ArrayList<>();
                for (List<Log.Entry> localLog : threadLogs.values()) {
                        globalLog.addAll(localLog);
                }
                return globalLog.toArray(new Log.Entry[0]);
        }

        public void reset() {
                for (int i = 0; i < head.next.length; i++) {
                        head.next[i] = new AtomicMarkableReference<LockFreeSkipListLocalLog.Node<T>>(tail, false);
                }
                // TODO: Clear the log if you have one.
                threadLogs.clear();
        }

        // Helper method to log actions
        private void logAction(Log.Method method, int key, boolean result, long timestamp) {
                if (timestamp <= 0.0) timestamp = System.nanoTime();
                long threadId = Thread.currentThread().getId();
                threadLogs.putIfAbsent(threadId, new ArrayList<>());
                // The independent list by threadId for logging.
                List<Log.Entry> localLog = threadLogs.get(threadId);
                localLog.add(new Log.Entry(method, key, result, timestamp));
        }
}
