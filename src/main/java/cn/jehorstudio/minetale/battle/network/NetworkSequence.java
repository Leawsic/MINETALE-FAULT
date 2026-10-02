package cn.jehorstudio.minetale.battle.network;

public record NetworkSequence(long value) implements Comparable<NetworkSequence> {
    public static final NetworkSequence ZERO = new NetworkSequence(0L);

    public NetworkSequence {
        if (value < 0L) {
            throw new IllegalArgumentException("network sequence must be >= 0.");
        }
    }

    public NetworkSequence next() {
        if (this.value == Long.MAX_VALUE) {
            throw new IllegalStateException("network sequence overflow.");
        }
        return new NetworkSequence(this.value + 1L);
    }

    public boolean newerThan(NetworkSequence other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(NetworkSequence other) {
        return Long.compare(this.value, other.value);
    }
}
