package com.jessetech.recipebalancer.core.io;

/** 配方 / 场景数据不合法（对应 Python {@code io.RecipeSchemaError}）。 */
public class SchemaException extends IllegalArgumentException {

    public SchemaException(String message) {
        super(message);
    }

    public SchemaException(String message, Throwable cause) {
        super(message, cause);
    }
}
