package de.hhu.bsinfo.observatory.benchmark;

import de.hhu.bsinfo.observatory.benchmark.Benchmark.Mode;
import de.hhu.bsinfo.observatory.benchmark.result.Status;

public class MessagingLatencyOperation extends LatencyOperation {

    MessagingLatencyOperation(Benchmark benchmark, Mode mode, int operationCount, int operationSize) {
        super(benchmark, mode, operationCount, operationSize);
    }

    @Override
    boolean needsFilledReceiveQueue() {
        return !(getMode() == Mode.SEND);
    }

    @Override
    Status warmUp(int operationCount) {
        if(getMode() == Mode.SEND) {
            for(int i = 0; i < operationCount; i++) {
                Status status = getBenchmark().sendSingleMessage();

                if(status != Status.OK) {
                    return status;
                }
            }

            return Status.OK;
        } else {
            return getBenchmark().receiveMultipleMessage(operationCount);
        }
    }

    @Override
    Status execute() {
        if(getMode() == Mode.SEND) {
            for(int i = 0; i < getMeasurement().getOperationCount(); i++) {
                getMeasurement().startSingleMeasurement();
                Status status = getBenchmark().sendSingleMessage();
                getMeasurement().stopSingleMeasurement();

                if(status != Status.OK) {
                    return status;
                }
            }

            getMeasurement().finishMeasuring();

            return Status.OK;
        } else {
            return getBenchmark().receiveMultipleMessage(getMeasurement().getOperationCount());
        }
    }
}
