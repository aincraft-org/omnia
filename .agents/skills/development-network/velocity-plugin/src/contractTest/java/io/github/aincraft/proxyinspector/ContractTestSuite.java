package io.github.aincraft.proxyinspector;

public final class ContractTestSuite {
    private ContractTestSuite() {
    }

    public static void main(String[] args) throws Exception {
        PunishmentServiceContractTest.main(args);
        DurationParserContractTest.main(args);
        CommandSupportContractTest.main(args);
        AdminListenerContractTest.main(args);
        CommandRegistrationContractTest.main(args);
    }
}
