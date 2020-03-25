/*
 * Copyright (C) 2020 Heinrich-Heine-Universitaet Duesseldorf,
 * Institute of Computer Science, Department Operating Systems
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

#ifndef OBSERVATORY_BENCHMARK_H
#define OBSERVATORY_BENCHMARK_H

#include <log4cpp/Category.hh>
#include <nlohmann/json.hpp>
#include <detector/IbFabric.h>
#include "result/Status.h"
#include "BenchmarkPhase.h"
#include "observatory/util/SocketAddress.h"

namespace Observatory {

class Benchmark {

public:

    enum Mode {
        SEND, RECEIVE
    };

    enum RdmaMode {
        READ, WRITE
    };

public:

    Benchmark() = default;

    Benchmark(const Benchmark &other) = default;

    Benchmark& operator=(const Benchmark &other) = delete;

    virtual ~Benchmark() = default;

    void addBenchmarkPhase(const std::shared_ptr<BenchmarkPhase>& phase);

    void setParameter(const std::string &key, const std::string &value);

    const std::string& getParameter(const std::string &key, const std::string &defaultValue) const;

    uint8_t getParameter(const std::string &key, uint8_t defaultValue) const;

    uint16_t getParameter(const std::string &key, uint16_t defaultValue) const;

    uint32_t getParameter(const std::string &key, uint32_t defaultValue) const;

    uint64_t getParameter(const std::string &key, uint64_t defaultValue) const;

    int getOffChannelSocket() const;

    std::string getResultName() const;

    bool isServer() const;

    int getConnectionRetries() const;

    SocketAddress getBindAddress() const;

    SocketAddress getRemoteAddress() const;

    std::string getResultPath() const;

    int getIterationNumber() const;

    bool measureOverhead() const;

    Detector::IbPerfCounter& getPerfCounter();

    void setResultName(const std::string &resultName);

    void setServer(bool isServer);

    void setConnectionRetries(uint32_t connectionRetries);

    void setBindAddress(const SocketAddress &bindAddress);

    void setRemoteAddress(const SocketAddress &remoteAddress);

    void setResultPath(const std::string &resultPath);

    void setIterationNumber(uint32_t iterationNumber);

    void setDetectorConfig(const nlohmann::json &detectorConfig);

    Status setup();

    bool synchronize();

    void executePhases();

    virtual const char* getClassName() const = 0;

    virtual Observatory::Benchmark* clone() const = 0;

    virtual Status initialize() = 0;

    virtual Status isServer() = 0;

    virtual Status serve(SocketAddress &bindAddress) = 0;

    virtual Status connect(SocketAddress &bindAddress, SocketAddress &remoteAddress) = 0;

    virtual Status prepare(uint32_t operationSize) = 0;

    virtual Status cleanup() = 0;

    virtual Status fillReceiveQueue() = 0;

    virtual Status sendMultipleMessage(uint32_t messageCount) = 0;

    virtual Status receiveMultipleMessage(uint32_t messageCount) = 0;

    virtual Status performMultipleRdmaOperations(RdmaMode mode, uint32_t operationCount) = 0;

    virtual Status sendSingleMessage() = 0;

    virtual Status performSingleRdmaOperation(RdmaMode mode) = 0;

    virtual Status performPingPongIterationServer() = 0;

    virtual Status performPingPongIterationClient() = 0;

private:

    bool sendSync();

    bool receiveSync();

private:

    static const constexpr char *SYNC_SIGNAL = "SYNC";

    log4cpp::Category &LOGGER = log4cpp::Category::getInstance("Benchmark");

    std::string resultName;
    uint32_t iterationNumber{};

    bool server{};
    int connectionRetries{};

    SocketAddress bindAddress;
    SocketAddress remoteAddress;

    nlohmann::json detectorConfig;

    int offChannelSocket{};

    std::shared_ptr<Detector::IbFabric> fabric;
    Detector::IbPerfCounter *perfCounter{};

    std::string resultPath;

    std::map<std::string, std::string> parameters;
    std::vector<std::shared_ptr<BenchmarkPhase>> phases;

};

}

#endif