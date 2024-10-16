import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicMarkableReference;

public class LockFreeSkipListGlobalLog<T extends Comparable<T>> implements LockFreeSet<T> {
        /* Number of levels */
        private static final int MAX_LEVEL = 16;
        private final Node<T> head = new Node<T>();
        private final Node<T> tail = new Node<T>();
        private static final LockFreeQueue<Log.Entry> logQueue = new LockFreeQueue<>();


        public LockFreeSkipListGlobalLog() {
                for (int i = 0; i < head.next.length; i++) {
                        head.next[i] = new AtomicMarkableReference<LockFreeSkipListGlobalLog.Node<T>>(tail, false);
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
                        boolean found = find(x, preds, succs);
                        if (found) {
                                // TODO: Lock around the unsuccessful linearization point of add
                                logAction(Log.Method.ADD, x.hashCode(), false);
                                return false;
                        } else {
                                Node<T> newNode = new Node(x, topLevel);
                                for (int level = bottomLevel; level <= topLevel; level++) {
                                        Node<T> succ = succs[level];
                                        newNode.next[level].set(succ, false);
                                }
                                Node<T> pred = preds[bottomLevel];
                                Node<T> succ = succs[bottomLevel];
                                if (!pred.next[bottomLevel].compareAndSet(succ, newNode, false, false)) {
                                        continue;
                                }
                                // TODO: Lock around the successful linearization point of add
                                logAction(Log.Method.ADD, x.hashCode(),true);
                                for (int level = bottomLevel + 1; level <= topLevel; level++) {
                                        while (true) {
                                                pred = preds[level];
                                                succ = succs[level];
                                                if (pred.next[level].compareAndSet(succ, newNode, false, false))
                                                        break;
                                                find(x, preds, succs);
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
                        boolean found = find(x, preds, succs);
                        if (!found) {
                                // TODO: Lock around the unsuccessful linearization point of remove
                                logAction(Log.Method.REMOVE, x.hashCode(), false);
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
                                        boolean iMarkedIt = nodeToRemove.next[bottomLevel].compareAndSet(succ, succ, false, true);
                                        succ = succs[bottomLevel].next[bottomLevel].get(marked);
                                        if (iMarkedIt) {
                                                // TODO: Lock around the successful linearization point of remove
                                                logAction(Log.Method.REMOVE, x.hashCode(), true);
                                                find(x, preds, succs);
                                                return true;
                                        } else if (marked[0]) {
                                                // TODO: Lock around the unsuccessful linearization point of remove
                                                logAction(Log.Method.REMOVE, x.hashCode(), false);
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
                for (int level = MAX_LEVEL; level >= bottomLevel; level--) {
                        curr = pred.next[level].getReference();
                        while (true) {
                                succ = curr.next[level].get(marked);
                                while (marked[0]) {
                                        curr = succ;
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
                // TODO: Lock around the linearization point of contains
                logAction(Log.Method.CONTAINS, x.hashCode(), result);
                return result;
        }

        private boolean find(T x, Node<T>[] preds, Node<T>[] succs) {
                int bottomLevel = 0;
                boolean[] marked = {false};
                boolean snip;
                Node<T> pred = null;
                Node<T> curr = null;
                Node<T> succ = null;
retry:
                while (true) {
                        pred = head;
                        for (int level = MAX_LEVEL; level >= bottomLevel; level--) {
                                curr = pred.next[level].getReference();
                                while (true) {
                                        succ = curr.next[level].get(marked);
                                        while (marked[0]) {
                                                snip = pred.next[level].compareAndSet(curr, succ, false, false);
                                                if (!snip) continue retry;
                                                curr = succ;
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
                        return curr.value != null && x.compareTo(curr.value) == 0;
                }
        }

        public Log.Entry[] getLog() {
                // TODO: This should fetch the log from the skiplist.
                List<Log.Entry> globalLog = new ArrayList<>();
                Log.Entry logEntry;
                while ((logEntry = logQueue.dequeue()) != null) {
                        globalLog.add(logEntry);
                }
                return globalLog.toArray(new Log.Entry[0]);
        }

        public void reset() {
                for (int i = 0; i < head.next.length; i++) {
                        head.next[i] = new AtomicMarkableReference<LockFreeSkipListGlobalLog.Node<T>>(tail, false);
                }
                // TODO: Clear the log if you have one.
                logQueue.clear();
        }

        // Helper method to log actions
        private void logAction(Log.Method method, int key, boolean result) {
                long timestamp = System.nanoTime();
                logQueue.enqueue(new Log.Entry(method, key, result, timestamp));
        }
}
