/*
 * Copyright 2018 Confluent Inc.
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

package io.confluent.ksql.serde;

import com.google.errorprone.annotations.Immutable;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.ksql.logging.processing.ProcessingLogContext;
import io.confluent.ksql.util.KsqlConfig;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;

@Immutable
public abstract class KsqlSerdeFactory {

  private final Format format;

  protected KsqlSerdeFactory(final Format format) {
    this.format = format;
  }

  public Format getFormat() {
    return format;
  }

  public abstract Serde<Struct> createSerde(
      Schema schema,
      KsqlConfig ksqlConfig,
      Supplier<SchemaRegistryClient> schemaRegistryClientFactory,
      String loggerNamePrefix,
      ProcessingLogContext processingLogContext);

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof KsqlSerdeFactory)) {
      return false;
    }
    final KsqlSerdeFactory that = (KsqlSerdeFactory) o;
    return format == that.format;
  }

  @Override
  public int hashCode() {
    return Objects.hash(format);
  }
}
