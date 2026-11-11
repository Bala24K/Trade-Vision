package io.tradevision.engine.runtime;

import io.tradevision.engine.Command;
import io.tradevision.engine.EngineListener;

/**
 * Engine listener that also sees command and batch boundaries, which an outbound publisher needs to
 * (a) correlate events to the request that caused them and (b) withhold acknowledgements until the
 * journaler (a parallel consumer) has made the request durable.
 */
public interface SequencedListener extends EngineListener {
    /** About to apply {@code command}. Events until {@link #onCommandEnd} were caused by it. */
    default void onCommandStart(Command command) {}

    default void onCommandEnd(Command command) {}

    /**
     * Every command up to {@code lastSequence} has been applied. Their outputs may be <i>staged</i> now but
     * must only be released to clients once {@code EngineRunner#durableSequence()} reaches them.
     */
    default void onBatchApplied(long lastSequence) {}

    /** Engine thread is idle; a good moment for low-priority work like conflated market-data flushes. */
    default void onIdle() {}
}
