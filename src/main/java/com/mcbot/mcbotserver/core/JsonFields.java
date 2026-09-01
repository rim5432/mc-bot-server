package com.mcbot.mcbotserver.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/**
 * Shared field-access primitives for boundary-D JSON codecs. Centralizes
 * the "has-or-throw" check so codec bodies stay straight-line and every
 * JsonObject field read funnels through one checked entry point.
 *
 * <p>Contract: see ledger entry (core nullness strategy: JSON field access
 * converges on a single checked entry point; the get-deref gate sees zero
 * chained JsonObject accesses in codec bodies).
 */
// contract: see boundaries.md section A (core stays engine-free; Gson is a
//           plain JVM library, not an MC import)
public final class JsonFields {

    private JsonFields() {}

    /**
     * Require a field to exist on the object.
     *
     * @param obj the JSON object to read from; never null
     * @param key the field name; never null
     * @return the field's value; never null
     * @throws JsonParseException when the object is null or the field is
     *         absent
     */
    public static JsonElement require(JsonObject obj, String key) {
        if (obj == null) {
            throw new JsonParseException("cannot read field '" + key + "' from null object");
        }
        if (!obj.has(key)) {
            throw new JsonParseException("payload lacks required field: '" + key + "'");
        }
        return obj.get(key);
    }
}
