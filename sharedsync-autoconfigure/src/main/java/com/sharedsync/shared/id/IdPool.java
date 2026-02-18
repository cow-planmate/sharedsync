package com.sharedsync.shared.id;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

/**
 * 개별 엔티티 타입의 ID Pool 관리 클래스.
 * DB 시퀀스에서 미리 할당받은 ID를 Thread-safe하게 제공합니다.
 */
@Slf4j
public class IdPool {

    private final String sequenceName;
    private final int allocationSize;
    private final ConcurrentLinkedQueue<Long> pool = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean refilling = new AtomicBoolean(false);

    /**
     * Pool이 이 임계값 이하로 떨어지면 비동기 리필을 트리거합니다.
     */
    private static final double REFILL_THRESHOLD_RATIO = 0.2;

    public IdPool(String sequenceName, int allocationSize) {
        this.sequenceName = sequenceName;
        this.allocationSize = allocationSize;
    }

    public String getSequenceName() {
        return sequenceName;
    }

    public int getAllocationSize() {
        return allocationSize;
    }

    /**
     * Pool에서 다음 ID를 꺼냅니다.
     * Pool이 비어있으면 null을 반환합니다 (호출자가 동기적으로 리필해야 함).
     */
    public Long poll() {
        return pool.poll();
    }

    /**
     * Pool에 ID를 추가합니다.
     */
    public void offer(Long id) {
        pool.offer(id);
    }

    /**
     * Pool에 여러 ID를 일괄 추가합니다.
     */
    public void addAll(java.util.Collection<Long> ids) {
        pool.addAll(ids);
    }

    /**
     * Pool의 현재 크기를 반환합니다.
     */
    public int size() {
        return pool.size();
    }

    /**
     * Pool이 비어있는지 확인합니다.
     */
    public boolean isEmpty() {
        return pool.isEmpty();
    }

    /**
     * 비동기 리필이 필요한지 확인합니다.
     */
    public boolean needsRefill() {
        return pool.size() <= (int) (allocationSize * REFILL_THRESHOLD_RATIO);
    }

    /**
     * 리필 플래그를 CAS로 설정합니다 (중복 리필 방지).
     * 
     * @return true면 리필 시작 가능, false면 이미 리필 중
     */
    public boolean tryStartRefill() {
        return refilling.compareAndSet(false, true);
    }

    /**
     * 리필 완료 후 플래그를 해제합니다.
     */
    public void finishRefill() {
        refilling.set(false);
    }
}
