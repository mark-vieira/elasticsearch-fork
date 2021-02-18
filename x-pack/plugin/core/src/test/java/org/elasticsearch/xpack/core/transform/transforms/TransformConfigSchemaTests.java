/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.core.transform.transforms;

import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.ToXContent.Params;
import org.elasticsearch.test.AbstractSchemaValidationTestCase;
import org.elasticsearch.xpack.core.transform.TransformField;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

public class TransformConfigSchemaTests extends AbstractSchemaValidationTestCase<TransformConfig> {

    protected static Params TO_XCONTENT_PARAMS = new ToXContent.MapParams(
        Collections.singletonMap(TransformField.EXCLUDE_GENERATED, "true")
    );

    @Override
    protected TransformConfig createTestInstance() {
        return TransformConfigTests.randomTransformConfig();
    }

    @Override
    protected InputStream getJsonSchema() throws IOException {
        return getDataInputStream("/rest-api-spec/schema/transform_config.schema.json");
    }

    @Override
    protected ToXContent.Params getToXContentParams() {
        return TO_XCONTENT_PARAMS;
    }
}
