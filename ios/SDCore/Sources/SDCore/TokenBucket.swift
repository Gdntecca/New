import Foundation

/// Token bucket for the global traffic mode. `bytesPerSecond == 0` means unlimited (no overhead).
public final class TokenBucket {
    private let lock = NSLock()
    private var available: Double = 0
    private var last: TimeInterval
    private let clock: () -> TimeInterval
    public var bytesPerSecond: Int64 {
        get { lock.lock(); defer { lock.unlock() }; return rate }
        set { lock.lock(); rate = max(0, newValue); lock.unlock() }
    }
    private var rate: Int64 = 0

    public init(clock: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) {
        self.clock = clock
        self.last = clock()
    }

    /// Takes `n` bytes; returns how long the caller should pause (0 when within budget).
    public func consume(_ n: Int) -> TimeInterval {
        lock.lock(); defer { lock.unlock() }
        guard rate > 0 else { return 0 }
        let now = clock()
        available = min(Double(rate), available + (now - last) * Double(rate))
        last = now
        available -= Double(n)
        return available < 0 ? -available / Double(rate) : 0
    }
}
