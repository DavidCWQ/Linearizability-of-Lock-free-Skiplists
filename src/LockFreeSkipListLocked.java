import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicMarkableReference;

public class LockFreeSkipListLocked<T extends Comparable<T>> implements LockFreeSet<T> {
        /* Number of levels */
        private static final int MAX_LEVEL = 16;
        private static final ReentrantLock lock = new ReentrantLock(); // For time sampling
        private final Node<T> head = new Node<T>();
        private final Node<T> tail = new Node<T>();
        private final List<Log.Entry> logs = new ArrayList<>(); // For logging LP

        public LockFreeSkipListLocked() {
                for (int i = 0; i < head.next.length; i++) {
                        head.next[i] = new AtomicMarkableReference<LockFreeSkipListLocked.Node<T>>(tail, false);
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
                        boolean found = find(x, preds, succs, Log.Method.ADD);
                        if (found) {
                                return false; // (duplicate element)
                        } else {
                                Node<T> newNode = new Node(x, topLevel);
                                for (int level = bottomLevel; level <= topLevel; level++) {
                                        Node<T> succ = succs[level];
                                        newNode.next[level].set(succ, false);
                                }
                                Node<T> pred = preds[bottomLevel];
                                Node<T> succ = succs[bottomLevel];

                                // TODO: Lock around the successful linearization point of add
                                logActionStart();
                                if (!pred.next[bottomLevel].compareAndSet(succ, newNode, false, false)) {
                                        logActionStop(); continue;
                                }
                                else {
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
                        boolean found = find(x, preds, succs, Log.Method.REMOVE);
                        if (!found) {
                                return false; // (node not found)
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
                                        // TODO: Lock around the (un)successful linearization point of remove
                                        logActionStart();
                                        boolean iMarkedIt = nodeToRemove.next[bottomLevel].compareAndSet(succ, succ, false, true);
                                        if (iMarkedIt) {
                                                logAction(Log.Method.REMOVE, x.hashCode(), true, -1);
                                        }
                                        else if (marked[0]){
                                                logAction(Log.Method.FAKE_REMOVE, x.hashCode(), false, -1);
                                        }
                                        else {
                                                logActionStop();
                                        }
                                        succ = succs[bottomLevel].next[bottomLevel].get(marked);
                                        if (iMarkedIt) {
                                                find(x, preds, succs, Log.Method.EMPTY);
                                                return true;
                                        } else if (marked[0]) {
                                                return false; // (node already marked for removal)
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
                        logActionStart();
                        curr = pred.next[level].getReference(); // Can be LP point
                        time.add(System.nanoTime());
                        logActionStop();
                        while (true) {
                                succ = curr.next[level].get(marked);
                                while (marked[0]) {
                                        // TODO: Log around the linearization point of contains
                                        logActionStart();
                                        curr = succ; // Can be LP point
                                        time.add(System.nanoTime());
                                        logActionStop();
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
                logActionStart();
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
                                logActionStart();
                                curr = pred.next[level].getReference(); // Can be LP point
                                time.add(System.nanoTime());
                                logActionStop();
                                while (true) {
                                        succ = curr.next[level].get(marked);
                                        while (marked[0]) {
                                                snip = pred.next[level].compareAndSet(curr, succ, false, false);
                                                if (!snip) continue retry;
                                                // TODO: Log around the unsuccessful LP of add or remove
                                                logActionStart();
                                                curr = succ; // Can be LP point
                                                time.add(System.nanoTime());
                                                logActionStop();
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
                        logActionStart();
                        boolean isFound = curr.value != null && x.compareTo(curr.value) == 0;

                        if (isFound && method == Log.Method.ADD) {
                                logAction(Log.Method.ADD, x.hashCode(), false, time.get(time.size() - 1));
                        }
                        else if (!isFound && method == Log.Method.REMOVE) {
                                logAction(Log.Method.REMOVE, x.hashCode(), false, time.get(time.size() - 1));
                        }
                        else {
                                logActionStop();
                        }
                        return isFound;
                }
        }

        public Log.Entry[] getLog() {
                // TODO: This should fetch the log from the skiplist.
                lock.lock();
                try {
                        return logs.toArray(new Log.Entry[0]);
                } finally {
                        lock.unlock();
                }
        }

        public void reset() {
                for (int i = 0; i < head.next.length; i++) {
                        head.next[i] = new AtomicMarkableReference<LockFreeSkipListLocked.Node<T>>(tail, false);
                }
                // TODO: Clear the log if you have one.
                lock.lock();
                try {
                        logs.clear();
                } finally {
                        lock.unlock();
                }
        }

        // Helper method to log actions

        /**
         * lock.lock()
         */
        private void logActionStart() {
                lock.lock();
        }

        /**
         * lock.unlock()
         */
        private void logActionStop() {
                lock.unlock();
        }

        /**
         * Must call `logActionStart()` before `logAction()`.
         * This method logs the timestamp and then lock.unlock().
         */
        private void logAction(Log.Method method, int key, boolean result, long timestamp) {
                try {
                        if (timestamp <= 0.0) timestamp = System.nanoTime();
                        logs.add(new Log.Entry(method, key, result, timestamp));
                } finally {
                        lock.unlock();
                }
        }
}
