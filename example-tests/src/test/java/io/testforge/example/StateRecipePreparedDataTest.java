package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.data.Generators;
import io.testforge.data.RunUniqueValues;
import io.testforge.data.prepared.Prepared;
import io.testforge.data.prepared.PreparedDataProvider;
import io.testforge.data.prepared.PreparedParameterResolver;
import io.testforge.flow.FlowContext;
import io.testforge.flow.FlowResult;
import io.testforge.flow.FlowStep;
import io.testforge.state.StatePreparation;
import io.testforge.state.StatePreparedDataProvider;
import io.testforge.state.StateRecipe;
import io.testforge.state.StateRecipeExecutor;
import io.testforge.state.StateRequest;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootTest
@ExtendWith(PreparedParameterResolver.class)
class StateRecipePreparedDataTest {

    @Autowired
    StateRecipeExecutor states;

    @Autowired
    StateRecipe<DemoTicket, DemoTicketState> ticketRecipe;

    @Test
    void injectsDomainObjectPreparedByStateRecipe(@Prepared(tags = "approved") DemoTicket ticket) {
        assertThat(ticket.id()).startsWith("ticket-");
        assertThat(ticket.status()).isEqualTo("approved");
    }

    @Test
    void exposesFlowPathForPreparedState() {
        StatePreparation<DemoTicket, DemoTicketState> preparation =
                states.prepareDetailed(ticketRecipe, List.of("state:approved", "tenant:demo"));

        assertThat(preparation.object().status()).isEqualTo("approved");
        assertThat(preparation.flowResult().path())
                .containsExactly(DemoTicketState.NEW, DemoTicketState.SUBMITTED, DemoTicketState.APPROVED);
        assertThat(preparation.request().hasTag("tenant:demo")).isTrue();
    }

    @TestConfiguration
    static class DemoStateConfig {

        @Bean
        StateRecipe<DemoTicket, DemoTicketState> ticketRecipe(RunUniqueValues uniqueValues) {
            return new DemoTicketRecipe(uniqueValues);
        }

        @Bean
        PreparedDataProvider<DemoTicket> ticketPreparedDataProvider(
                StateRecipe<DemoTicket, DemoTicketState> recipe,
                StateRecipeExecutor executor) {
            return StatePreparedDataProvider.of(recipe, executor);
        }
    }

    private enum DemoTicketState {
        NEW,
        SUBMITTED,
        APPROVED
    }

    private record DemoTicket(String id, String status) {
    }

    private static class DemoTicketRecipe implements StateRecipe<DemoTicket, DemoTicketState> {

        private static final String TICKET = "ticket";

        private final RunUniqueValues uniqueValues;

        DemoTicketRecipe(RunUniqueValues uniqueValues) {
            this.uniqueValues = uniqueValues;
        }

        @Override
        public Class<DemoTicket> type() {
            return DemoTicket.class;
        }

        @Override
        public DemoTicketState initialState(StateRequest request) {
            return DemoTicketState.NEW;
        }

        @Override
        public DemoTicketState targetState(StateRequest request) {
            return switch (request.requireTargetTag()) {
                case "submitted" -> DemoTicketState.SUBMITTED;
                case "approved" -> DemoTicketState.APPROVED;
                default -> throw new IllegalArgumentException("Unsupported ticket state tag: " + request.tags());
            };
        }

        @Override
        public Collection<? extends FlowStep<DemoTicketState>> steps(StateRequest request, FlowContext context) {
            return List.of(
                    step(DemoTicketState.NEW, current -> {
                        String id = "ticket-" + uniqueValues.generate("ticket", Generators.alphanumeric(8), 20);
                        current.put(TICKET, new DemoTicket(id, "submitted"));
                        return DemoTicketState.SUBMITTED;
                    }),
                    step(DemoTicketState.SUBMITTED, current -> {
                        DemoTicket ticket = current.get(TICKET, DemoTicket.class);
                        current.put(TICKET, new DemoTicket(ticket.id(), "approved"));
                        return DemoTicketState.APPROVED;
                    }));
        }

        @Override
        public DemoTicket materialize(FlowResult<DemoTicketState> result, StateRequest request) {
            return DemoTicket.class.cast(result.contextSnapshot().get(TICKET));
        }

        private FlowStep<DemoTicketState> step(
                DemoTicketState state,
                java.util.function.Function<FlowContext, DemoTicketState> body) {
            return new FlowStep<>() {
                @Override
                public DemoTicketState state() {
                    return state;
                }

                @Override
                public DemoTicketState execute(FlowContext context) {
                    return body.apply(context);
                }
            };
        }
    }
}
