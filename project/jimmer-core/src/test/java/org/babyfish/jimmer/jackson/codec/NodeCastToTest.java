package org.babyfish.jimmer.jackson.codec;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * The same test sources run under both codec test suites (Jackson 2 {@code test} and
 * Jackson 3 {@code testJackson3}), so the codec is resolved from the classpath instead of
 * referencing both implementations.
 *
 * <p>Numeric casts must return the exact declared type. A floating JSON literal is already
 * a rounded double node, so high precision is asserted against an explicitly built
 * {@code DecimalNode} rather than a float literal.</p>
 */
public class NodeCastToTest {

    @Test
    public void testIntegerNumericCasts() throws Exception {
        Node node = nodeFor("10");
        Assertions.assertEquals(Integer.valueOf(10), node.castTo(Integer.class));
        Assertions.assertEquals(Long.valueOf(10L), node.castTo(Long.class));
        Assertions.assertEquals(new BigDecimal("10"), node.castTo(BigDecimal.class));
        Assertions.assertEquals(BigInteger.valueOf(10), node.castTo(BigInteger.class));
    }

    @Test
    public void testRepresentableDecimalBigDecimalCast() throws Exception {
        // 10.5 is exactly representable; this only claims the node's typed value, not
        // recovery of lexical precision from an already-parsed double.
        Assertions.assertEquals(new BigDecimal("10.5"), nodeFor("10.5").castTo(BigDecimal.class));
    }

    @Test
    public void testExactDecimalBigDecimalCast() throws Exception {
        String literal = "0.123456789012345678901234567890";
        Assertions.assertEquals(
                new BigDecimal(literal),
                exactDecimalNode(literal).castTo(BigDecimal.class)
        );
    }

    @Test
    public void testBigIntegerBeyondLong() throws Exception {
        String json = "9223372036854775808123";
        Node node = nodeFor(json);
        Assertions.assertEquals(new BigInteger(json), node.castTo(BigInteger.class));
        Assertions.assertEquals(new BigDecimal(new BigInteger(json)), node.castTo(BigDecimal.class));
    }

    @Test
    public void testExistingPrimitiveCasts() throws Exception {
        Node node = nodeFor("{\"b\":true,\"s\":\"hello\",\"i\":7}");
        Assertions.assertEquals(Boolean.TRUE, node.get("b").castTo(Boolean.class));
        Assertions.assertEquals("hello", node.get("s").castTo(String.class));
        Assertions.assertEquals(Integer.valueOf(7), node.get("i").castTo(Integer.class));
    }

    @Test
    public void testNullAndNonNumericNodesKeepCodecSemantics() throws Exception {
        Assertions.assertTrue(nodeFor("null").isNull());
        // The wrapper still advertises the caster for any node; the string value semantics
        // stay codec-native (Jackson 2 coercive, Jackson 3 strict), so only castability is
        // asserted here to avoid broadening or pinning that difference.
        Assertions.assertTrue(nodeFor("\"not-a-number\"").canCastTo(BigDecimal.class));
    }

    private static Node exactDecimalNode(String literal) throws Exception {
        BigDecimal value = new BigDecimal(literal);
        boolean v3 = JsonCodecDetector.loadJsonCodecProvider().create().version() == JacksonVersion.V3;
        Class<?> nodeType = Class.forName(
                v3 ? "tools.jackson.databind.JsonNode" : "com.fasterxml.jackson.databind.JsonNode"
        );
        Class<?> decimalType = Class.forName(
                v3 ? "tools.jackson.databind.node.DecimalNode" : "com.fasterxml.jackson.databind.node.DecimalNode"
        );
        Object node = decimalType.getMethod("valueOf", BigDecimal.class).invoke(null, value);
        Class<?> wrapperType = Class.forName(
                v3 ? "org.babyfish.jimmer.jackson.v3.NodeV3" : "org.babyfish.jimmer.jackson.v2.NodeV2"
        );
        return (Node) wrapperType.getConstructor(nodeType).newInstance(node);
    }

    private static Node nodeFor(String json) throws Exception {
        return JsonCodecDetector.loadJsonCodecProvider().create().treeReader().read(json);
    }
}
