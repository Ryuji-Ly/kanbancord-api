package com.kanbancord_api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonConfigTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JsonConfig().largeIntegersAsStrings());

    record Sample(long primitiveId, Long boxedId, Long taskId, Integer count) {
    }

    @Test
    void discordSizedIntegersBecomeStrings_smallOnesStayNumbers() throws Exception {
        assertEquals("{\"primitiveId\":\"1161001645698715698\",\"boxedId\":\"871775879427153971\",\"taskId\":42,\"count\":3}",
                mapper.writeValueAsString(new Sample(1161001645698715698L, 871775879427153971L, 42L, 3)));
    }

    @Test
    void theBoundaryIsJavaScriptsLargestExactInteger() throws Exception {
        assertEquals("9007199254740991", mapper.writeValueAsString(JsonConfig.MAX_SAFE_INTEGER));
        assertEquals("\"9007199254740992\"", mapper.writeValueAsString(JsonConfig.MAX_SAFE_INTEGER + 1));
        assertEquals("\"-9007199254740992\"", mapper.writeValueAsString(-JsonConfig.MAX_SAFE_INTEGER - 1));
    }

    @Test
    void appliesInsideMapsAndLists_likeAuditChanges() throws Exception {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("userId", 1161001645698715698L);
        changes.put("roles", List.of(BigInteger.valueOf(1176083036576690216L), 7L));
        assertEquals("{\"userId\":\"1161001645698715698\",\"roles\":[\"1176083036576690216\",7]}",
                mapper.writeValueAsString(changes));
    }
}
