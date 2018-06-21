/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.operations;

import org.gradle.internal.Factory;

import javax.annotation.Nullable;

public final class DeferredOperationFinishEvent  implements OperationFinishEvent {
    private final long startTime;
    private final long endTime;
    private final Factory<?> producer;

    private boolean realized;
    private Throwable failure;
    private Object result;

    public DeferredOperationFinishEvent(long startTime, long endTime, Factory<?> deferredProducer) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.producer = deferredProducer;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    @Nullable
    public Throwable getFailure() {
        realize();
        return failure;
    }

    @Nullable
    public Object getResult() {
        realize();
        return result;
    }

    private void realize() {
        if (!realized) {
            try {
                result = producer.create();
            } catch (Throwable e) {
                failure = e;
            } finally {
                realized = true;
            }
        }
    }
}
