/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.AdditionalPropertiesValidator;
import com.networknt.schema.ItemsValidator;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.JsonValidator;
import com.networknt.schema.PropertiesValidator;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Map.Entry;
import java.util.Set;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

public abstract class AbstractSchemaValidationTestCase<T extends ToXContent> extends ESTestCase {

    public final void testSchema() throws IOException {
        BytesReference xContent = XContentHelper.toXContent(createTestInstance(), XContentType.JSON, getToXContentParams(), false);

        ObjectMapper mapper = new ObjectMapper();
        SchemaValidatorsConfig config = new SchemaValidatorsConfig();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

        JsonSchema jsonSchema = factory.getSchema(mapper.readTree(getJsonSchema()), config);

        // ensure the schema meets certain criteria like not empty, strictness
        assertTrue("found empty schema", jsonSchema.getValidators().size() > 0);
        assertTrue("schema lacks at least 1 required field", jsonSchema.hasRequiredValidator());
        assertSchemaStrictness(jsonSchema.getValidators().values(), jsonSchema.getSchemaPath());

        JsonNode jsonTree = mapper.readTree(xContent.streamInput());

        Set<ValidationMessage> errors = jsonSchema.validate(jsonTree);
        assertThat("Schema validation failed for: " + jsonTree.toPrettyString(), errors, is(empty()));
    }

    protected abstract T createTestInstance();

    protected abstract InputStream getJsonSchema() throws IOException;

    protected ToXContent.Params getToXContentParams() {
        return ToXContent.EMPTY_PARAMS;
    }

    /**
     * Enforce that the schema as well as all sub schemas define all properties
     *
     * This uses an implementation detail of the schema validation library: If
     * strict validation is turned on (`"additionalProperties": false`)
     *
     * The schema validator injects an instance of AdditionalPropertiesValidator if thats set,
     * if AdditionalPropertiesValidator is absent the test fails
     */
    private void assertSchemaStrictness(Collection<JsonValidator> validatorSet, String path) {
        boolean additionalPropertiesValidatorFound = false;
        boolean subSchemaFound = false;

        for (JsonValidator validator : validatorSet) {
            if (validator instanceof PropertiesValidator) {
                subSchemaFound = true;
                PropertiesValidator propertiesValidator = (PropertiesValidator) validator;
                for (Entry<String, JsonSchema> subSchema : propertiesValidator.getSchemas().entrySet()) {
                    assertSchemaStrictness(subSchema.getValue().getValidators().values(), propertiesValidator.getSchemaPath());
                }
            } else if (validator instanceof ItemsValidator) {
                ItemsValidator itemValidator = (ItemsValidator) validator;
                if (itemValidator.getSchema() != null) {
                    assertSchemaStrictness(itemValidator.getSchema().getValidators().values(), itemValidator.getSchemaPath());
                }
                if (itemValidator.getTupleSchema() != null) {
                    for (JsonSchema subSchema : itemValidator.getTupleSchema()) {
                        assertSchemaStrictness(subSchema.getValidators().values(), itemValidator.getSchemaPath());
                    }
                }
            } else if (validator instanceof AdditionalPropertiesValidator) {
                additionalPropertiesValidatorFound = true;
            }
        }

        // if not a leaf, additional property strictness must be set
        assertTrue(
            "the schema must have additional properties set to false (\"additionalProperties\": false) in all (sub) schemas, "
                + "missing at least for path: "
                + path,
            subSchemaFound == false || additionalPropertiesValidatorFound
        );
    }
}
