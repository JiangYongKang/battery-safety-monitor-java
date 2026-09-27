package com.github.highcumontoa.batterysafetymonitorjava.assess;

import com.github.highcumontoa.batterysafetymonitorjava.model.TelemetrySample;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 单电池包的连续采样滑动窗口。
 * <p>仅接受时间戳严格晚于窗口内最新采样的样本（乱序到达返回 false，
 * 防止旧数据覆盖新结论）；按时间跨度与容量双重截断。</p>
 * <p>非线程安全，由调用方按 packId 串行化访问。</p>
 */
public class SlidingWindow {

    private final long windowMillis;
    private final int maxSize;
    private final Deque<TelemetrySample> samples = new ArrayDeque<>();

    public SlidingWindow(long windowMillis, int maxSize) {
        this.windowMillis = windowMillis;
        this.maxSize = maxSize;
    }

    /**
     * 追加采样。
     *
     * @return true 已入窗；false 为乱序/重复时间戳，被拒绝入窗
     */
    public boolean add(TelemetrySample sample) {
        TelemetrySample latest = samples.peekLast();
        if (latest != null && sample.getTimestampMillis() <= latest.getTimestampMillis()) {
            return false;
        }
        samples.addLast(sample);
        evict(sample.getTimestampMillis());
        return true;
    }

    private void evict(long newestMillis) {
        while (!samples.isEmpty()
                && (samples.peekFirst().getTimestampMillis() < newestMillis - windowMillis
                    || samples.size() > maxSize)) {
            samples.pollFirst();
        }
    }

    public List<TelemetrySample> snapshot() {
        return new ArrayList<>(samples);
    }

    public int size() {
        return samples.size();
    }
}
