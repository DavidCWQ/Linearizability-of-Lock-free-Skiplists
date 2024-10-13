import java.util.concurrent.atomic.AtomicReference;

public class LockFreeQueue<T> {
    private static class Node<T> {
        final T value;
        final AtomicReference<Node<T>> next;

        Node(T value) {
            this.value = value;
            this.next = new AtomicReference<>(null);
        }
    }

    private final AtomicReference<Node<T>> head;
    private final AtomicReference<Node<T>> tail;

    public LockFreeQueue() {
        Node<T> dummy = new Node<>(null);
        head = new AtomicReference<>(dummy);
        tail = new AtomicReference<>(dummy);
    }

    public void enqueue(T value) {
        Node<T> newNode = new Node<>(value);
        while (true) {
            Node<T> currentTail = tail.get();
            Node<T> nextNode = currentTail.next.get();

            if (currentTail == tail.get()) { // Ensure tail has not changed
                if (nextNode == null) { // If no next node, try to link new node
                    if (currentTail.next.compareAndSet(null, newNode)) {
                        tail.compareAndSet(currentTail, newNode); // Move tail to the new node
                        return;
                    }
                } else {
                    tail.compareAndSet(currentTail, nextNode); // Tail is lagging, move it forward
                }
            }
        }
    }

    public T dequeue() {
        while (true) {
            Node<T> currentHead = head.get();
            Node<T> currentTail = tail.get();
            Node<T> nextNode = currentHead.next.get();

            if (currentHead == head.get()) { // Ensure head has not changed
                if (currentHead == currentTail) { // Queue is empty
                    if (nextNode == null) {
                        return null; // Queue is empty
                    }
                    tail.compareAndSet(currentTail, nextNode); // Move tail forward
                } else {
                    if (head.compareAndSet(currentHead, nextNode)) {
                        return nextNode.value; // Return the dequeued value
                    }
                }
            }
        }
    }

    public void clear() {
        while (true) {
            Node<T> currentHead = head.get();
            Node<T> currentTail = tail.get();
            Node<T> nextHead = currentHead.next.get();

            // Attempt to move the head to the next node until we reach the tail
            if (currentHead == head.get()) {
                if (currentHead == currentTail) {
                    // We are at the end of the queue
                    head.compareAndSet(currentHead, currentTail); // Set head to tail
                    return; // Clear complete
                }

                if (nextHead != null) {
                    // Clear the reference to the next node
                    head.compareAndSet(currentHead, nextHead); // Move head forward
                }
            }
        }
    }

    public boolean isEmpty() {
        return head.get().next.get() == null; // Check if the queue is empty
    }
}
