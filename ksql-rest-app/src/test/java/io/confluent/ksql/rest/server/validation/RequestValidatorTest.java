/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.rest.server.validation;

import static io.confluent.ksql.parser.ParserMatchers.configured;
import static io.confluent.ksql.parser.ParserMatchers.preparedStatement;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.confluent.ksql.KsqlExecutionContext;
import io.confluent.ksql.function.InternalFunctionRegistry;
import io.confluent.ksql.metastore.MetaStoreImpl;
import io.confluent.ksql.metastore.MutableMetaStore;
import io.confluent.ksql.metastore.model.KsqlStream;
import io.confluent.ksql.parser.DefaultKsqlParser;
import io.confluent.ksql.parser.KsqlParser.ParsedStatement;
import io.confluent.ksql.parser.tree.CreateStream;
import io.confluent.ksql.parser.tree.Explain;
import io.confluent.ksql.parser.tree.SetProperty;
import io.confluent.ksql.parser.tree.Statement;
import io.confluent.ksql.schema.ksql.KsqlSchema;
import io.confluent.ksql.services.SandboxedServiceContext;
import io.confluent.ksql.services.ServiceContext;
import io.confluent.ksql.services.TestServiceContext;
import io.confluent.ksql.statement.Injector;
import io.confluent.ksql.statement.InjectorChain;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlConstants;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.KsqlStatementException;
import io.confluent.ksql.util.Sandbox;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class RequestValidatorTest {

  private static final KsqlSchema SCHEMA = KsqlSchema.of(SchemaBuilder
      .struct()
      .field("val", Schema.OPTIONAL_STRING_SCHEMA)
      .build());
  private static final String SOME_STREAM_SQL = "CREATE STREAM x WITH (value_format='json', kafka_topic='x');";

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Mock
  SandboxEngine ksqlEngine;
  @Mock
  KsqlConfig ksqlConfig;
  @Mock
  StatementValidator<?> statementValidator;
  @Mock
  Injector schemaInjector;
  @Mock
  Injector topicInjector;

  private ServiceContext serviceContext;
  private MutableMetaStore metaStore;
  private RequestValidator validator;
  private KsqlExecutionContext executionContext;

  @Before
  public void setUp() {
    metaStore = new MetaStoreImpl(new InternalFunctionRegistry());
    when(ksqlEngine.parse(any()))
        .thenAnswer(inv -> new DefaultKsqlParser().parse(inv.getArgument(0)));
    when(ksqlEngine.prepare(any()))
        .thenAnswer(invocation ->
            new DefaultKsqlParser().prepare(invocation.getArgument(0), metaStore));
    executionContext = ksqlEngine;
    serviceContext = SandboxedServiceContext.create(TestServiceContext.create());
    when(ksqlConfig.getInt(KsqlConfig.KSQL_ACTIVE_PERSISTENT_QUERY_LIMIT_CONFIG))
        .thenReturn(Integer.MAX_VALUE);
    when(schemaInjector.inject(any())).thenAnswer(inv -> inv.getArgument(0));
    when(topicInjector.inject(any())).thenAnswer(inv -> inv.getArgument(0));

    final KsqlStream<?> source = mock(KsqlStream.class);
    when(source.getName()).thenReturn("SOURCE");
    when(source.getSchema()).thenReturn(SCHEMA);

    final KsqlStream<?> sink = mock(KsqlStream.class);
    when(sink.getName()).thenReturn("SINK");
    when(sink.getSchema()).thenReturn(SCHEMA);

    metaStore.putSource(source);
    metaStore.putSource(sink);

    givenRequestValidator(ImmutableMap.of());
  }

  @Test
  public void shouldCallStatementValidator() {
    // Given:
    givenRequestValidator(
        ImmutableMap.of(CreateStream.class, statementValidator)
    );

    final List<ParsedStatement> statements =
        givenParsed(SOME_STREAM_SQL);

    // When:
    validator.validate(serviceContext, statements, ImmutableMap.of(), "sql");

    // Then:
    verify(statementValidator, times(1)).validate(
        argThat(is(configured(preparedStatement(instanceOf(CreateStream.class))))),
        eq(executionContext),
        any()
    );
  }

  @Test
  public void shouldExecuteOnEngineIfNoCustomExecutor() {
    // Given:
    final List<ParsedStatement> statements = givenParsed("SET 'property'='value';");

    // When:
    validator.validate(serviceContext, statements, ImmutableMap.of(), "sql");

    // Then:
    verify(ksqlEngine, times(1)).execute(
        argThat(configured(preparedStatement(instanceOf(SetProperty.class))))
    );
  }

  @Test
  public void shouldThrowExceptionIfValidationFails() {
    // Given:
    givenRequestValidator(
        ImmutableMap.of(CreateStream.class, statementValidator)
    );
    doThrow(new KsqlException("Fail"))
        .when(statementValidator).validate(any(), any(), any());

    final List<ParsedStatement> statements =
        givenParsed(SOME_STREAM_SQL);

    // Expect:
    expectedException.expect(KsqlException.class);
    expectedException.expectMessage("Fail");

    // When:
    validator.validate(serviceContext, statements, ImmutableMap.of(), "sql");
  }

  @Test
  public void shouldThrowIfNoValidatorAvailable() {
    // Given:
    final List<ParsedStatement> statements =
        givenParsed("EXPLAIN X;");

    // Expect:
    expectedException.expect(KsqlStatementException.class);
    expectedException.expectMessage("Do not know how to validate statement");

    // When:
    validator.validate(serviceContext, statements, ImmutableMap.of(), "sql");
  }

  @Test
  public void shouldThrowIfTooManyPersistentQueries() {
    // Given:
    when(ksqlConfig.getInt(KsqlConfig.KSQL_ACTIVE_PERSISTENT_QUERY_LIMIT_CONFIG)).thenReturn(1);

    final List<ParsedStatement> statements =
        givenParsed(
            "CREATE STREAM sink AS SELECT * FROM source;"
                + "CREATE STREAM sink2 as SELECT * FROM sink;"
        );

    // Expect:
    expectedException.expect(KsqlException.class);
    expectedException.expectMessage("persistent queries to exceed the configured limit");

    // When:
    validator.validate(serviceContext, statements, ImmutableMap.of(), "sql");
  }

  @Test
  public void shouldNotThrowIfManyNonPersistentQueries() {
    // Given:
    givenRequestValidator(
        ImmutableMap.of(
            CreateStream.class, StatementValidator.NO_VALIDATION,
            Explain.class, StatementValidator.NO_VALIDATION)
    );
    when(ksqlConfig.getInt(KsqlConfig.KSQL_ACTIVE_PERSISTENT_QUERY_LIMIT_CONFIG)).thenReturn(1);

    final List<ParsedStatement> statements =
        givenParsed(
            "CREATE STREAM a WITH (kafka_topic='a', value_format='json');"
                + "EXPLAIN x;"
        );

    // Expect Nothing:
    // When:
    validator.validate(serviceContext, statements, ImmutableMap.of(), "sql");
  }

  @Test
  public void shouldValidateRunScript() {
    // Given:
    final Map<String, Object> props = ImmutableMap.of(
        KsqlConstants.LEGACY_RUN_SCRIPT_STATEMENTS_CONTENT,
        SOME_STREAM_SQL);

    givenRequestValidator(
        ImmutableMap.of(CreateStream.class, statementValidator)
    );

    final List<ParsedStatement> statements = givenParsed("RUN SCRIPT '/some/script.sql';");

    // When:
    validator.validate(serviceContext, statements, props, "sql");

    // Then:
    verify(statementValidator, times(1)).validate(
        argThat(is(configured(preparedStatement(instanceOf(CreateStream.class))))),
        eq(executionContext),
        any()
    );
  }


  @Test
  public void shouldThrowIfInvalidOverriddenProperty() {
    // Given:
    final Map<String, Object> props = ImmutableMap.of(
        "invalid.property", "foo");
    givenRequestValidator(
        ImmutableMap.of(CreateStream.class, statementValidator)
    );
    final List<ParsedStatement> statements =
        givenParsed(
            "CREATE STREAM a WITH (kafka_topic='a', value_format='json');"
        );

    // Expect:
    expectedException.expect(KsqlException.class);
    expectedException.expectMessage("Invalid config property: invalid.property");

    // When:
    validator.validate(serviceContext, statements, props, "sql");
  }

  @Test
  public void shouldValidateForValidOverriddenProperty() {
    // Given:
    final Map<String, Object> props = ImmutableMap.of(
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    givenRequestValidator(
        ImmutableMap.of(CreateStream.class, statementValidator)
    );
    final List<ParsedStatement> statements =
        givenParsed(
            "CREATE STREAM a WITH (kafka_topic='a', value_format='json');"
        );

    // When:
    validator.validate(serviceContext, statements, props, "sql");

    // Then:
    verify(statementValidator, times(1)).validate(
        argThat(is(configured(preparedStatement(instanceOf(CreateStream.class))))),
        eq(executionContext),
        any()
    );

  }

  @Test
  public void shouldThrowIfServiceContextIsNotSandbox() {
    // Given:
    serviceContext = mock(ServiceContext.class);
    givenRequestValidator(ImmutableMap.of());

    // Expect:
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Expected sandbox");

    // When:
    validator.validate(serviceContext, ImmutableList.of(), ImmutableMap.of(), "sql");
  }

  @Test
  public void shouldThrowIfSnapshotSupplierReturnsNonSandbox() {
    // Given:
    executionContext = mock(KsqlExecutionContext.class);
    givenRequestValidator(ImmutableMap.of());

    // Expect:
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Expected sandbox");

    // When:
    validator.validate(serviceContext, ImmutableList.of(), ImmutableMap.of(), "sql");
  }

  private List<ParsedStatement> givenParsed(final String sql) {
    return new DefaultKsqlParser().parse(sql);
  }

  private void givenRequestValidator(
      Map<Class<? extends Statement>, StatementValidator<?>> customValidators
  ) {
    validator = new RequestValidator(
        customValidators,
        (ec, sc) -> InjectorChain.of(schemaInjector, topicInjector),
        () -> executionContext,
        ksqlConfig
    );
  }

  @Sandbox
  private interface SandboxEngine extends KsqlExecutionContext { }

}
