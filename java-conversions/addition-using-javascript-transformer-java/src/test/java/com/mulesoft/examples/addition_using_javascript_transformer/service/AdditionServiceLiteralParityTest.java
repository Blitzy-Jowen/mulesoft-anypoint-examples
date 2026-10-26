/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.addition_using_javascript_transformer.service;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parity of {@link AdditionService} with the JavaScript transformer of flow {@code javascript-calculatorFlow1}
 * (addition-using-javascript-transformer/src/main/app/javascript-calculator.xml:8-12) for bodies of literal data,
 * and its failures where the original failed.
 *
 * <p>Every expected response, Rhino error text and Rhino error type below was produced by running the original
 * script, {@code var list = eval("(" + payload + ")"); var sum = 0; for (var i in list) sum += list[i];
 * payload = sum;}, with the body as {@code payload} on Rhino 1.7R4 behind {@code javax.script:js-engine:1.1:jdk14},
 * the versions Mule 3.8.0 pins, and copied unchanged; none was derived by reasoning about JavaScript. A numeric
 * result is shown as MEL renders it, with {@link Double#toString(double)}, inside {@code Sum is: <payload>.}.
 *
 * <p>Bodies of JavaScript code carry the response the original computed by evaluating them, which this service
 * intentionally refuses to do, and the nesting bound is this service's own, since Rhino's depends on its thread's
 * stack.
 */
class AdditionServiceLiteralParityTest {

    private static final String CODE_REJECTION =
            "is JavaScript code, and the request body is read as literal data, never evaluated";

    /** The stack of the thread the depth tests run on. */
    private static final long SMALL_STACK_BYTES = 256 * 1024;

    private final AdditionService service = new AdditionService();

    /** Bodies of literal data, each with the response the original produced. */
    static Stream<Arguments> literalData() {
        return Stream.of(
                arguments("{a:0x10}", "Sum is: 16.0."),
                arguments("{a:010}", "Sum is: 8.0."),
                arguments("{a:undefined}", "Sum is: NaN."),
                arguments("{a:[1,,2]}", "Sum is: 01,,2."),
                arguments("{ \"a\" : 1, \"b\": 2 }", "Sum is: 3.0."),
                arguments("{a:3,b:4}", "Sum is: 7.0."),
                arguments("{a:0X1f}", "Sum is: 31.0."),
                arguments("{a:019}", "Sum is: 19.0."),
                arguments("{a:0189}", "Sum is: 189.0."),
                arguments("{a:08}", "Sum is: 8.0."),
                arguments("{a:08.5}", "Sum is: 8.5."),
                arguments("{a:09.5}", "Sum is: 9.5."),
                arguments("{a:08e1}", "Sum is: 80.0."),
                arguments("{a:00}", "Sum is: 0.0."),
                arguments("{a:0.}", "Sum is: 0.0."),
                arguments("{a:5.e1}", "Sum is: 50.0."),
                arguments("{a:.5}", "Sum is: 0.5."),
                arguments("{a:1e400}", "Sum is: Infinity."),
                arguments("{a:0x}", "Sum is: NaN."),
                arguments("{a:1E-3}", "Sum is: 0.001."),
                arguments("{a:-0x10}", "Sum is: -16.0."),
                arguments("{a:-010}", "Sum is: -8.0."),
                arguments("{a:0x1000000000000081,b:\"\"}", "Sum is: 1152921504606847200."),
                arguments("{a:0x20000000000001,b:\"\"}", "Sum is: 9007199254740992."),
                arguments("{a:0x20000000000003,b:\"\"}", "Sum is: 9007199254740996."),
                arguments("{a:0" + "7".repeat(25) + ",b:\"\"}", "Sum is: 3.777893186295716e+22."),
                arguments("{a:123456789012345678901234567890,b:\"\"}", "Sum is: 1.2345678901234568e+29."),
                arguments("{a:0x" + "f".repeat(300) + "}", "Sum is: Infinity."),
                arguments("{a:0" + "7".repeat(400) + "}", "Sum is: Infinity."),
                arguments("{a:0x" + "0".repeat(50) + "1}", "Sum is: 1.0."),
                arguments("{a:-5}", "Sum is: -5.0."),
                arguments("{a:- 5}", "Sum is: -5.0."),
                arguments("{a:- -5}", "Sum is: 5.0."),
                arguments("{a:-+5}", "Sum is: -5.0."),
                arguments("{a:+ +5}", "Sum is: 5.0."),
                arguments("{a:-Infinity}", "Sum is: -Infinity."),
                arguments("{a:-NaN}", "Sum is: NaN."),
                arguments("{a:-undefined}", "Sum is: NaN."),
                arguments("{a:-\"5\"}", "Sum is: -5.0."),
                arguments("{a:+[5]}", "Sum is: 5.0."),
                arguments("{a:+[]}", "Sum is: 0.0."),
                arguments("{a:+{}}", "Sum is: NaN."),
                arguments("{a:-[1,2]}", "Sum is: NaN."),
                arguments("{a:+true}", "Sum is: 1.0."),
                arguments("{a:-null,b:\"\"}", "Sum is: 0."),
                arguments("{a:-+-\"3\"}", "Sum is: 3.0."),
                arguments("{a:-{__proto__:[5]}}", "Sum is: -5.0."),
                arguments("{a:+[\" 0x1F \"]}", "Sum is: 31.0."),
                arguments("{a:+{valueOf:1}}", "Sum is: NaN."),
                arguments("{a:-\n-->x\n1}", "Sum is: -1.0."),
                arguments("{a:+\"0x10\"}", "Sum is: 16.0."),
                arguments("{a:+\"-0x10\"}", "Sum is: -16.0."),
                arguments("{a:+\" 12 \"}", "Sum is: 12.0."),
                arguments("{a:+\"\"}", "Sum is: 0.0."),
                arguments("{a:+\"  \"}", "Sum is: 0.0."),
                arguments("{a:+\"1e3\"}", "Sum is: 1000.0."),
                arguments("{a:+\"Infinity\"}", "Sum is: Infinity."),
                arguments("{a:+\"-Infinity\"}", "Sum is: -Infinity."),
                arguments("{a:+\" Infinity \"}", "Sum is: Infinity."),
                arguments("{a:+\"infinity\"}", "Sum is: NaN."),
                arguments("{a:+\"Infinityx\"}", "Sum is: NaN."),
                arguments("{a:+\"1,2\"}", "Sum is: NaN."),
                arguments("{a:+\"0x\"}", "Sum is: NaN."),
                arguments("{a:+\"0x1g\"}", "Sum is: 1.0."),
                arguments("{a:+\"+0x\"}", "Sum is: NaN."),
                arguments("{a:+\"0x-1\"}", "Sum is: NaN."),
                arguments("{a:+\"\u00a012\"}", "Sum is: 12.0."),
                arguments("{a:+\"\u001c12\"}", "Sum is: NaN."),
                arguments("{a:+\"\ufeff5\"}", "Sum is: 5.0."),
                arguments("{a:+\"1.\"}", "Sum is: 1.0."),
                arguments("{a:+\".5\"}", "Sum is: 0.5."),
                arguments("{a:+\"5e\"}", "Sum is: NaN."),
                arguments("{a:+\"+5\"}", "Sum is: 5.0."),
                arguments("{a:+\"--5\"}", "Sum is: NaN."),
                arguments("{a:+\"1d\"}", "Sum is: NaN."),
                arguments("{a:+\"1_0\"}", "Sum is: NaN."),
                arguments("{a:+\"010\"}", "Sum is: 10.0."),
                arguments("{a:+\"1e+5\"}", "Sum is: 100000.0."),
                arguments("{a:+\"-.5e-1\"}", "Sum is: -0.05."),
                arguments("{a:+\"0x10.5\"}", "Sum is: 16.0."),
                arguments("{a:\"\\x41\\v\"}", "Sum is: 0A\u000b."),
                arguments("{a:\"\\x4\"}", "Sum is: 0x4."),
                arguments("{a:\"\\xg\"}", "Sum is: 0xg."),
                arguments("{a:\"\\x41\\x4G\"}", "Sum is: 0Ax4G."),
                arguments("{a:\"\\u0041\"}", "Sum is: 0A."),
                arguments("{a:\"\\u004\"}", "Sum is: 0u004."),
                arguments("{a:\"\\u004g\"}", "Sum is: 0u004g."),
                arguments("{a:\"\\u{41}\"}", "Sum is: 0u{41}."),
                arguments("{a:\"\\0\"}", "Sum is: 0\u0000."),
                arguments("{a:\"\\101\\400\\377\\08\\q\\'\\\"\\\\\\/\"}", "Sum is: 0A 0\u00ff\u00008q'\"\\/."),
                arguments("{a:\"\\378\"}", "Sum is: 0\u001f8."),
                arguments("{a:\"\\477\"}", "Sum is: 0'7."),
                arguments("{a:\"\\1234\"}", "Sum is: 0S4."),
                arguments("{a:\"\\0008\"}", "Sum is: 0\u00008."),
                arguments("{a:\"\\8\\9\"}", "Sum is: 089."),
                arguments("{a:\"\\b\\f\\n\\r\\t\\v\"}", "Sum is: 0\b\f\n\r\t\u000b."),
                arguments("{a:'it\\'s'}", "Sum is: 0it's."),
                arguments("{a:\"x\\\ny\"}", "Sum is: 0xy."),
                arguments("{a:\"x\\\r\ny\"}", "Sum is: 0xy."),
                arguments("{a:\"x\\\u2028y\"}", "Sum is: 0xy."),
                arguments("{a:\"x\u200by\"}", "Sum is: 0x\u200by."),
                arguments("{a:\"x\ufeffy\"}", "Sum is: 0x\ufeffy."),
                arguments("{a:\"\t\"}", "Sum is: 0\t."),
                arguments("{a:\"\\u00e9\"}", "Sum is: 0\u00e9."),
                arguments("{a:\"\ud83d\ude00\"}", "Sum is: 0\ud83d\ude00."),
                arguments("{a:1}//x\r\n", "Sum is: 1.0."),
                arguments("{a:1/*c*/}", "Sum is: 1.0."),
                arguments("{a:1/**/}", "Sum is: 1.0."),
                arguments("{a:1<!--x\n}", "Sum is: 1.0."),
                arguments("{a:1\n-->x\n}", "Sum is: 1.0."),
                arguments("{a:1\n  -->x\n}", "Sum is: 1.0."),
                arguments("{a:1//x\u2028}", "Sum is: 1.0."),
                arguments("{a:1}\r\n", "Sum is: 1.0."),
                arguments("{a:1}/**/", "Sum is: 1.0."),
                arguments("{a:1})//", "Sum is: 1.0."),
                arguments("{a:1});//", "Sum is: 1.0."),
                arguments("{a:1});;//", "Sum is: 1.0."),
                arguments("{a:1}) //", "Sum is: 1.0."),
                arguments("{a:1})<!--", "Sum is: 1.0."),
                arguments("{a:1})\n//", "Sum is: 1.0."),
                arguments("{a:1});\n;//x", "Sum is: 1.0."),
                arguments("{a:1})\n-->", "Sum is: 1.0."),
                arguments("\ufeff{a:1}", "Sum is: 1.0."),
                arguments("{a:\u200b1}", "Sum is: 1.0."),
                arguments("\u00a0{a:1}\u00a0", "Sum is: 1.0."),
                arguments("\u3000{a:1}", "Sum is: 1.0."),
                arguments("\u000b{a:1}\f", "Sum is: 1.0."),
                arguments("{a:1\u200b2}", "Sum is: 12.0."),
                arguments("{a:und\u200befined}", "Sum is: NaN."),
                arguments("[1, /*c*/ ,2]", "Sum is: 3.0."),
                arguments("{if:1,true:2,null:3,undefined:4,get:5,set:6,function:7,var:8,class:9}", "Sum is: 45.0."),
                arguments("{let:1,yield:2,enum:3}", "Sum is: 6.0."),
                arguments("{0x10:1,16:2}", "Sum is: 2.0."),
                arguments("{1.5:\"a\",.5:\"b\",1e3:\"c\",01:\"d\"}", "Sum is: 0abcd."),
                arguments("{\"1\":1,1:2}", "Sum is: 2.0."),
                arguments("{a:1,b:2,a:5}", "Sum is: 7.0."),
                arguments("{b:1,2:\"x\",a:3,1:\"y\"}", "Sum is: 1x3y."),
                arguments("{a:1,\"a\":2}", "Sum is: 2.0."),
                arguments("{\\u0061:1,a:2}", "Sum is: 2.0."),
                arguments("{a\\u0062:1}", "Sum is: 1.0."),
                arguments("{\\u0074rue:1,true:2}", "Sum is: 3.0."),
                arguments("{\\u0069f:1}", "Sum is: 1.0."),
                arguments("{\"-0\":1,\"0\":2}", "Sum is: 3.0."),
                arguments("{\"00\":1,\"0\":2}", "Sum is: 3.0."),
                arguments("{1e21:1,\"1e+21\":2}", "Sum is: 2.0."),
                arguments("{4294967296:1,\"4294967296\":2}", "Sum is: 2.0."),
                arguments("{\u00e9:1,$:2,_:3}", "Sum is: 6.0."),
                arguments("{a\u0000b:1}", "Sum is: 1.0."),
                arguments("{a:1,\"\":2}", "Sum is: 3.0."),
                arguments("{a:\"if\",[:1}", "Sum is: 0if1."),
                arguments("{a:true,;:3}", "Sum is: 4.0."),
                arguments("{a:null,]:5}", "Sum is: 5.0."),
                arguments("{a:\"if\",):7}", "Sum is: 0if7."),
                arguments("{a:\"tru\\u0065\",[:1}", "Sum is: 0true1."),
                arguments("[,]", "Sum is: 0.0."),
                arguments("[,,]", "Sum is: 0.0."),
                arguments("[1,]", "Sum is: 1.0."),
                arguments("[1,,]", "Sum is: 1.0."),
                arguments("[1,,2]", "Sum is: 3.0."),
                arguments("[,1]", "Sum is: 1.0."),
                arguments("[,\"x\"]", "Sum is: 0x."),
                arguments("[1,2,3,]", "Sum is: 6.0."),
                arguments("[undefined]", "Sum is: NaN."),
                arguments("{a:[,]}", "Sum is: 0."),
                arguments("{a:[undefined]}", "Sum is: 0."),
                arguments("{a:[null]}", "Sum is: 0."),
                arguments("{a:[[]]}", "Sum is: 0."),
                arguments("{a:[[],[]]}", "Sum is: 0,."),
                arguments("{a:[{}]}", "Sum is: 0[object Object]."),
                arguments("{a:[1,[2,[3,,]],,]}", "Sum is: 01,2,3,,."),
                arguments("[[1,,],[,]]", "Sum is: 01,."),
                arguments("{a:[1,2],b:[3]}", "Sum is: 01,23."),
                arguments("{a:[true,false,undefined,null,NaN,-0,1e21,1e-7]}",
                        "Sum is: 0true,false,,,NaN,0,1e+21,1e-7."),
                arguments("[\"a\",[1]]", "Sum is: 0a1."),
                arguments("{a:[1,null,\"x\",[2,3]]}", "Sum is: 01,,x,2,3."),
                arguments("{}", "Sum is: 0.0."),
                arguments("[]", "Sum is: 0.0."),
                arguments("5", "Sum is: 0.0."),
                arguments("\"ab\"", "Sum is: 0.0."),
                arguments("'ab'", "Sum is: 0.0."),
                arguments("null", "Sum is: 0.0."),
                arguments("undefined", "Sum is: 0.0."),
                arguments("NaN", "Sum is: 0.0."),
                arguments("-5", "Sum is: 0.0."),
                arguments("true", "Sum is: 0.0."),
                arguments("-{a:1}", "Sum is: 0.0."),
                arguments("+[1]", "Sum is: 0.0."),
                arguments("-\"5\"", "Sum is: 0.0."),
                arguments("[1,2]", "Sum is: 3.0."),
                arguments("{a:{valueOf:1}}", "Sum is: 0[object Object]."),
                arguments("{a:[{valueOf:1}]}", "Sum is: 0[object Object]."),
                arguments("{a:{constructor:1,hasOwnProperty:2}}", "Sum is: 0[object Object]."),
                arguments("{toString:1,a:1}", "Sum is: 2.0."),
                arguments("{a:{},b:{}}", "Sum is: 0[object Object][object Object]."),
                arguments("{a:{a:1}}", "Sum is: 0[object Object]."),
                arguments("{a:1,b:{x:1}}", "Sum is: 1[object Object]."),
                arguments("{a:{__iterator__:1}}", "Sum is: 0[object Object]."),
                arguments("{a:{__parent__:null}}", "Sum is: 0[object Object]."),
                arguments("{a:{__proto__:{valueOf:1}}}", "Sum is: 0[object Object]."),
                arguments("{a:{valueOf:1,__proto__:[1]}}", "Sum is: 01."),
                arguments("[{__proto__:[1]}]", "Sum is: 01."),
                arguments("{a:{__proto__:[1,2]}}", "Sum is: 01,2."),
                arguments("{a:{__proto__:[]}}", "Sum is: 0."),
                arguments("{a:{__proto__:[1,2],length:1}}", "Sum is: 01."),
                arguments("{a:{__proto__:[1,2],length:\"1\"}}", "Sum is: 01."),
                arguments("{a:{__proto__:[1,2],length:\"0x2\"}}", "Sum is: 01,2."),
                arguments("{a:{__proto__:[1,2],length:true}}", "Sum is: 01."),
                arguments("{a:{__proto__:[1,2],length:null}}", "Sum is: 0."),
                arguments("{a:{__proto__:[1,2],length:undefined}}", "Sum is: 0."),
                arguments("{a:{__proto__:[1,2],length:{}}}", "Sum is: 0."),
                arguments("{a:{__proto__:[1,2],length:1.5}}", "Sum is: 01."),
                arguments("{a:{__proto__:[1,2],length:[3]}}", "Sum is: 01,2,."),
                arguments("{a:{__proto__:[1,2],length:4294967297}}", "Sum is: 01."),
                arguments("{a:{__proto__:[1,2],length:-4294967294}}", "Sum is: 01,2."),
                arguments("{a:{__proto__:[1,2],length:NaN}}", "Sum is: 0."),
                arguments("{a:{__proto__:[1,2],length:Infinity}}", "Sum is: 0."),
                arguments("{a:{__proto__:[1,2], 0:\"x\"}}", "Sum is: 0x,2."),
                arguments("{a:{__proto__:[1,2],\"-0\":\"x\"}}", "Sum is: 01,2."),
                arguments("{a:{__proto__:{__proto__:[1,2]},length:3}}", "Sum is: 01,2,."),
                arguments("{\"a\":{\"__proto__\":[],\"length\":3}}", "Sum is: 0,,."),
                arguments("{__proto__:{x:1},a:2}", "Sum is: 3.0."),
                arguments("{__proto__:{x:1,a:9},a:2}", "Sum is: 3.0."),
                arguments("{\"__proto__\":{x:1},a:2}", "Sum is: 3.0."),
                arguments("{\\u005f_proto__:{x:1}}", "Sum is: 1.0."),
                arguments("{__proto__:{x:1},__proto__:{y:2}}", "Sum is: 2.0."),
                arguments("{__proto__:{__proto__:{z:5}}}", "Sum is: 5.0."),
                arguments("{__proto__:[1,2],a:3}", "Sum is: 6.0."),
                arguments("{__proto__:[,2]}", "Sum is: 2.0."),
                arguments("{__proto__:{__proto__:[7,8]},x:1}", "Sum is: 16.0."),
                arguments("{__proto__:null,a:1}", "Sum is: 1.0."),
                arguments("{__proto__:undefined,a:1}", "Sum is: 1.0."),
                arguments("{__proto__:5,a:1}", "Sum is: 1.0."),
                arguments("{__proto__:\"ab\"}", "Sum is: 0.0."),
                arguments("{__parent__:5,a:1}", "Sum is: 1.0."),
                arguments("{__parent__:{x:1},a:1}", "Sum is: 1.0."),
                arguments("{a:\"o\", __proto__:{x:\"p\", a:\"q\"}, b:\"r\"}", "Sum is: 0orp."),
                arguments("{a:1,__proto__:[5,6],0:\"z\"}", "Sum is: 1z6."),
                arguments("{__proto__:[5,6],0:\"z\",1:\"y\"}", "Sum is: 0zy."),
                arguments("{__proto__:{b:1},b:\"x\"}", "Sum is: 0x."),
                arguments("{a:{__proto__:[],length:2,\"01\":\"x\",\"\":\"y\",0:\"z\"}}", "Sum is: 0z,."),
                arguments("{a:{__proto__:[],length:3,\"12345678901\":\"x\",1:\"y\"}}", "Sum is: 0,y,."),
                arguments("{a:-1,b:\"\"}", "Sum is: -1."),
                arguments("{a:-Infinity,b:\"\"}", "Sum is: -Infinity."),
                arguments("{a:\"x\",b:null}", "Sum is: 0xnull."),
                arguments("{a:\"x\",b:undefined}", "Sum is: 0xundefined.")
        );
    }

    /** Bodies whose conversion the original failed, each with Rhino's TypeError text. */
    static Stream<Arguments> typeErrors() {
        return Stream.of(
                arguments("{\"a\":{\"toString\":1}}", "TypeError: Cannot find default value for object."),
                arguments("{a:{toString:undefined}}", "TypeError: Cannot find default value for object."),
                arguments("{a:{\"toString\":null}}", "TypeError: Cannot find default value for object."),
                arguments("{a:{toString:{}}}", "TypeError: Cannot find default value for object."),
                arguments("{a:{valueOf:1,toString:1}}", "TypeError: Cannot find default value for object."),
                arguments("{a:[{toString:1}]}", "TypeError: Cannot find default value for object."),
                arguments("[{toString:1}]", "TypeError: Cannot find default value for object."),
                arguments("{a:\"x\",b:{toString:1}}", "TypeError: Cannot find default value for object."),
                arguments("{a:[1,[2,{toString:1}]]}", "TypeError: Cannot find default value for object."),
                arguments("{a:{__proto__:null}}", "TypeError: Cannot find default value for object."),
                arguments("{a:{__proto__:undefined}}", "TypeError: Cannot find default value for object."),
                arguments("{a:{__proto__:{toString:1}}}", "TypeError: Cannot find default value for object."),
                arguments("{a:{__proto__:[1],toString:2}}", "TypeError: Cannot find default value for object."),
                arguments("{a:{__proto__:{__proto__:null}}}", "TypeError: Cannot find default value for object."),
                arguments("{a:-{toString:1}}", "TypeError: Cannot find default value for object."),
                arguments("-{toString:1}", "TypeError: Cannot find default value for object."),
                arguments("{a:-{__proto__:null}}", "TypeError: Cannot find default value for object."),
                arguments("{a:{__proto__:[1,2],length:{toString:1}}}",
                        "TypeError: Cannot find default value for object."),
                arguments("{a:{__proto__:5}}", "TypeError: Method \"valueOf\" called on incompatible object."),
                arguments("{a:{__proto__:\"ab\"}}", "TypeError: Method \"valueOf\" called on incompatible object."),
                arguments("{a:{__proto__:true}}", "TypeError: Method \"valueOf\" called on incompatible object."),
                arguments("{a:+{__proto__:5}}", "TypeError: Method \"valueOf\" called on incompatible object."),
                arguments("{a:{__proto__:{__proto__:5}}}",
                        "TypeError: Method \"valueOf\" called on incompatible object."),
                arguments("[{__proto__:5}]", "TypeError: Method \"valueOf\" called on incompatible object."),
                arguments("{a:[{__proto__:5}]}", "TypeError: Method \"toString\" called on incompatible object."),
                arguments("{a:[{__proto__:\"x\"}]}", "TypeError: Method \"toString\" called on incompatible object."),
                arguments("{a:[{__proto__:false}]}", "TypeError: Method \"toString\" called on incompatible object."),
                arguments("-[{__proto__:5}]", "TypeError: Method \"toString\" called on incompatible object."),
                arguments("{__iterator__:1}", "TypeError: Invalid iterator value"),
                arguments("{__iterator__:1,a:2}", "TypeError: Invalid iterator value"),
                arguments("{a:2,__iterator__:null}", "TypeError: Invalid iterator value"),
                arguments("{\"__iterator__\":1}", "TypeError: Invalid iterator value"),
                arguments("{__proto__:{__iterator__:5},a:1}", "TypeError: Invalid iterator value"),
                arguments("{\\u005f_iterator__:1}", "TypeError: Invalid iterator value")
        );
    }

    /** Bodies Rhino rejected as invalid, each with Rhino's SyntaxError text. */
    static Stream<Arguments> syntaxErrors() {
        return Stream.of(
                arguments("{a:0xg}", "SyntaxError: missing } after property list"),
                arguments("{a:010.5}", "SyntaxError: missing } after property list"),
                arguments("{a:00.5}", "SyntaxError: missing } after property list"),
                arguments("{a:07e1}", "SyntaxError: missing } after property list"),
                arguments("{a:0x10.5}", "SyntaxError: missing } after property list"),
                arguments("{a:1e}", "SyntaxError: missing exponent"),
                arguments("{a:1e+}", "SyntaxError: missing exponent"),
                arguments("{a:0e}", "SyntaxError: missing exponent"),
                arguments("{a:0b11}", "SyntaxError: missing } after property list"),
                arguments("{a:--5}", "SyntaxError: Invalid decrement operand."),
                arguments("{a:++5}", "SyntaxError: Invalid increment operand."),
                arguments("{a}", "SyntaxError: missing : after property id"),
                arguments("{,}", "SyntaxError: invalid property id"),
                arguments("{a:1,,}", "SyntaxError: invalid property id"),
                arguments("{\"a\" 1}", "SyntaxError: missing : after property id"),
                arguments("{a:1;}", "SyntaxError: missing } after property list"),
                arguments("{a:1};", "SyntaxError: missing ) in parenthetical"),
                arguments("{a:1}<!--x", "SyntaxError: missing ) in parenthetical"),
                arguments("{a:1<!--x", "SyntaxError: missing } after property list"),
                arguments("{a:\"x\ny\"}", "SyntaxError: unterminated string literal"),
                arguments("{a:\"x\u2028y\"}", "SyntaxError: unterminated string literal"),
                arguments("{a:\"\\\u200b\"}", "SyntaxError: unterminated string literal"),
                arguments("{a:1/*x", "SyntaxError: unterminated comment"),
                arguments("\u001c{a:1}", "SyntaxError: illegal character"),
                arguments("\u0085{a:1}", "SyntaxError: illegal character"),
                arguments("{a:1}\u0000", "SyntaxError: illegal character"),
                arguments("{a:1,\n/*c*/-->x\n}", "SyntaxError: invalid property id"),
                arguments("{a:1,\n  /*c*/ -->x\n}", "SyntaxError: invalid property id"),
                arguments("{a:\"x\"}//x", "SyntaxError: missing ) in parenthetical"),
                arguments("{\"if\":1,[:2}", "SyntaxError: invalid property id"),
                arguments("{a:\"x\",[:1}", "SyntaxError: invalid property id"),
                arguments("{[:1}", "SyntaxError: invalid property id"),
                arguments("[1 2]", "SyntaxError: missing ] after element list"),
                arguments("{a:1}\n-->x", "SyntaxError: missing ) in parenthetical"),
                arguments("{a:\\u00zz}", "SyntaxError: invalid Unicode escape sequence"),
                arguments("{a\\x:1}", "SyntaxError: illegal character"),
                arguments("{a:\"x", "SyntaxError: unterminated string literal"),
                arguments("{a:1}}", "SyntaxError: missing ) in parenthetical"),
                arguments("{a:1})", "SyntaxError: missing ; before statement"),
                arguments("{a:1});", "SyntaxError: syntax error"),
                arguments("]", "SyntaxError: syntax error"),
                arguments("{a:1 2}", "SyntaxError: missing } after property list"),
                arguments("{a:1 \"b\"}", "SyntaxError: missing } after property list"),
                arguments("{a::1}", "SyntaxError: missing : after property id"),
                arguments("{a:5.toString}", "SyntaxError: missing } after property list"),
                arguments("{a:1.2.3}", "SyntaxError: missing } after property list"),
                arguments("{a:.e1}", "SyntaxError: syntax error"),
                arguments("{a:- -->x\n1}", "SyntaxError: syntax error"),
                arguments("{a:1\n--\n2}", "SyntaxError: missing } after property list"),
                arguments("{a:5++}", "SyntaxError: Invalid increment operand."),
                arguments("{a:\"if\",}:7}", "SyntaxError: missing ) in parenthetical"),
                arguments("{a:1})/**/", "SyntaxError: missing ; before statement"),
                arguments("{a:1});\n", "SyntaxError: syntax error"),
                arguments("{a:++-5}", "SyntaxError: syntax error"),
                arguments("{a:++\"s\"}", "SyntaxError: Invalid increment operand."),
                arguments("{a:++{}}", "SyntaxError: Invalid increment operand."),
                arguments("{a:++true}", "SyntaxError: Invalid increment operand."),
                arguments("{a:\\x}", "SyntaxError: illegal character"),
                arguments("{a://", "SyntaxError: Unexpected end of file"),
                arguments("[//", "SyntaxError: missing ] after element list")
        );
    }

    /**
     * Bodies the original failed on while evaluating or compiling JavaScript code, each with the type of Rhino's
     * error: undefined variables, invalid assignments, calls of non-functions and E4X operators.
     */
    static Stream<Arguments> codeTheOriginalFailedOn() {
        return Stream.of(
                arguments("{a:x}", "ReferenceError"),
                arguments("{a:infinity}", "ReferenceError"),
                arguments("{a:INF}", "ReferenceError"),
                arguments("{a:-INF}", "ReferenceError"),
                arguments("{a:\\u0074rue}", "ReferenceError"),
                arguments("{a:class}", "ReferenceError"),
                arguments("{a:let}", "ReferenceError"),
                arguments("{a:1in}", "SyntaxError"),
                arguments("{a:[1](0)}", "TypeError"),
                arguments("{a:1/*/*x*/*/}", "SyntaxError"),
                arguments("{a:1>>>=2}", "SyntaxError"),
                arguments("{a:1|=2}", "SyntaxError"),
                arguments("{a:1^=2}", "SyntaxError"),
                arguments("{a:1&=2}", "SyntaxError"),
                arguments("{a:1%=2}", "SyntaxError"),
                arguments("{a:1*=2}", "SyntaxError"),
                arguments("{a:1/=2}", "SyntaxError"),
                arguments("{a:1+=2}", "SyntaxError"),
                arguments("{a:1-=2}", "SyntaxError"),
                arguments("{a:1<<=2}", "SyntaxError"),
                arguments("{a:1>>=2}", "SyntaxError"),
                arguments("{a:1=2}", "SyntaxError"),
                arguments("{a:[]..x}", "TypeError"),
                arguments("{a:[].(1)}", "TypeError"),
                arguments("{a:x::y}", "ReferenceError"),
                arguments("{a:@x}", "TypeError"),
                arguments("{a:-x}", "ReferenceError"),
                arguments("{a:--(x)}", "ReferenceError"),
                arguments("{a:++/x/}", "SyntaxError")
        );
    }

    /** Bodies of JavaScript code, each with the response the original computed by evaluating it. */
    static Stream<Arguments> codeTheOriginalEvaluated() {
        return Stream.of(
                arguments("{a:sum}", "Sum is: NaN."),
                arguments("{a:list}", "Sum is: NaN."),
                arguments("{a:payload}", "Sum is: 0{a:payload}."),
                arguments("{a:function(){}}", "Sum is: 0\nfunction () {\n}\n."),
                arguments("{a:/x/g}", "Sum is: 0/x/g."),
                arguments("/x/", "Sum is: 0.0."),
                arguments("{a:<x/>}", "Sum is: 0."),
                arguments("{a:(1)}", "Sum is: 1.0."),
                arguments("({a:1})", "Sum is: 1.0."),
                arguments("{a:1+2}", "Sum is: 3.0."),
                arguments("{a:!0}", "Sum is: 1.0."),
                arguments("{a:void 0}", "Sum is: NaN."),
                arguments("{a:this}", "Sum is: 0[object Global]."),
                arguments("{get a(){return 1}}", "Sum is: 1.0."),
                arguments("1),(2", "Sum is: 0.0."),
                arguments("{a:--Infinity}", "Sum is: Infinity."),
                arguments("{a:++ undefined}", "Sum is: NaN."),
                arguments("{a:{b:1}.b}", "Sum is: 1.0."),
                arguments("{a:[5][0]}", "Sum is: 5.0."),
                arguments("{a:1}),({b:2}", "Sum is: 2.0."),
                arguments("{a:1?2:3}", "Sum is: 2.0."),
                arguments("{a:typeof 1}", "Sum is: 0number."),
                arguments("{a:1>>>2}", "Sum is: 0.0."),
                arguments("{a:1>>2}", "Sum is: 0.0."),
                arguments("{a:1<<2}", "Sum is: 4.0."),
                arguments("{a:1>=2}", "Sum is: 0.0."),
                arguments("{a:1<=2}", "Sum is: 1.0."),
                arguments("{a:1>2}", "Sum is: 0.0."),
                arguments("{a:1==1}", "Sum is: 1.0."),
                arguments("{a:1===1}", "Sum is: 1.0."),
                arguments("{a:1!=1}", "Sum is: 0.0."),
                arguments("{a:1!==1}", "Sum is: 0.0."),
                arguments("{a:1&&2}", "Sum is: 2.0."),
                arguments("{a:1||2}", "Sum is: 1.0."),
                arguments("{a:1&2}", "Sum is: 0.0."),
                arguments("{a:1|2}", "Sum is: 3.0."),
                arguments("{a:1^2}", "Sum is: 3.0."),
                arguments("{a:1%2}", "Sum is: 1.0."),
                arguments("{a:1*2}", "Sum is: 2.0."),
                arguments("{a:1/2}", "Sum is: 0.5."),
                arguments("{a:1-2}", "Sum is: -1.0."),
                arguments("{a:~1}", "Sum is: -2.0."),
                arguments("{a:[].length}", "Sum is: 0.0."),
                arguments("{a:1 instanceof Object}", "Sum is: 0.0."),
                arguments("{a:[1 for (x in [2])]}", "Sum is: 01."),
                arguments("{a:new Object()}", "Sum is: 0[object Object]."),
                arguments("{a:typeof x}", "Sum is: 0undefined."),
                arguments("{a:delete x}", "Sum is: 1.0."),
                arguments("{a:/=1/}", "Sum is: 0/=1/."),
                arguments("{a:[1,2]+[3]}", "Sum is: 01,23."),
                arguments("{a:+(1)}", "Sum is: 1.0."),
                arguments("{a:1<!0}", "Sum is: 0.0."),
                arguments("{a:1<!-2}", "Sum is: 0.0."),
                arguments("1,2", "Sum is: 0.0."),
                arguments("{a:1});\"5\".concat(", "Sum is: 0.0."),
                arguments("{a:++[1][0]}", "Sum is: 2.0.")
        );
    }

    /** Bodies of whitespace and comments only, each with the SyntaxError the original reported. */
    static Stream<Arguments> bodiesWithoutContent() {
        return Stream.of(
                arguments(" ", "SyntaxError: syntax error"),
                arguments("\n\t", "SyntaxError: syntax error"),
                arguments("/* only a comment */", "SyntaxError: syntax error"),
                arguments("// a comment", "SyntaxError: Unexpected end of file"),
                arguments("\u00a0<!-- x", "SyntaxError: Unexpected end of file"),
                arguments("\u200b", "SyntaxError: syntax error"),
                arguments("\ufeff", "SyntaxError: syntax error")
        );
    }

    /** Arrays and objects nested 1000 levels deep, the deepest accepted, with the original's response. */
    static Stream<Arguments> deepestNesting() {
        return Stream.of(
                arguments("[".repeat(1000) + "1" + "]".repeat(1000), "Sum is: 01."),
                arguments("{a:".repeat(1000) + "1" + "}".repeat(1000), "Sum is: 0[object Object]."),
                arguments("[".repeat(999) + "{a:1}" + "]".repeat(999), "Sum is: 0[object Object]."),
                arguments("{a:" + "-[".repeat(999) + "2" + "]".repeat(999) + "}", "Sum is: -2.0."),
                arguments("[" + "{a:-[".repeat(499) + "1" + "]}".repeat(499) + "]", "Sum is: 0[object Object].")
        );
    }

    /**
     * Bodies whose joins of inherited arrays produce millions of elements, alone or together, each with the response
     * the original produced; a long response is written as the expression it was compared equal to.
     */
    static Stream<Arguments> longJoins() {
        return Stream.of(
                arguments("{a:+{__proto__:[],length:16777217}}", "Sum is: NaN."),
                arguments("+{__proto__:[],length:16777217}", "Sum is: 0.0."),
                arguments("{a:+{__proto__:[],length:16777217},b:{__proto__:[],length:3}}", "Sum is: NaN,,."),
                arguments("{a:{__proto__:[],length:16777217}}", "Sum is: 0" + ",".repeat(16777216) + "."),
                arguments("{a:{__proto__:[\"x\"],length:16777217}}", "Sum is: 0x" + ",".repeat(16777216) + "."),
                arguments("{\"a\":{\"__proto__\":[],\"length\":8388609},\"b\":{\"__proto__\":[],\"length\":8388609}}",
                        "Sum is: 0" + ",".repeat(8388608) + ",".repeat(8388608) + ".")
        );
    }

    /** Arrays and objects nested 1001 levels deep. */
    static Stream<String> tooDeepNesting() {
        return Stream.of(
                "[".repeat(1001) + "1" + "]".repeat(1001),
                "{a:".repeat(1001) + "1" + "}".repeat(1001),
                "[".repeat(1000) + "{a:1}" + "]".repeat(1000)
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("literalData")
    void sumsLiteralDataAsTheOriginalDid(String body, String originalResponse) {
        assertEquals(originalResponse, service.handle(body.getBytes(UTF_8), UTF_8));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("typeErrors")
    void failsWithTheOriginalTypeError(String body, String rhinoMessage) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.sum(body));

        assertEquals(rhinoMessage, e.getMessage());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("syntaxErrors")
    void failsWithTheOriginalSyntaxError(String body, String rhinoMessage) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.sum(body));

        assertTrue(e.getMessage().startsWith(rhinoMessage + " (at offset "), e.getMessage());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("codeTheOriginalFailedOn")
    void failsOnCodeTheOriginalFailedOn(String body, String rhinoErrorType) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.sum(body),
                "the original failed with a " + rhinoErrorType);

        assertTrue(e.getMessage().contains(CODE_REJECTION) || e.getMessage().startsWith("SyntaxError: "),
                e.getMessage());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("codeTheOriginalEvaluated")
    void rejectsJavaScriptCodeTheOriginalEvaluated(String body, String originalResponse) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.sum(body),
                "the original evaluated it to " + originalResponse);

        assertTrue(e.getMessage().contains(CODE_REJECTION), e.getMessage());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("bodiesWithoutContent")
    void reportsNoContentForBodiesWithoutALiteral(String body, String rhinoMessage) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.handle(body.getBytes(UTF_8), UTF_8), "the original failed with " + rhinoMessage);

        assertEquals(AdditionService.NO_CONTENT, e.getMessage());
    }

    @Test
    void reportsNoContentForAnEmptyOrMissingBody() {
        // The original failed on both: eval("()") is a SyntaxError.
        assertEquals(AdditionService.NO_CONTENT,
                assertThrows(IllegalArgumentException.class, () -> service.handle(new byte[0], UTF_8)).getMessage());
        assertEquals(AdditionService.NO_CONTENT,
                assertThrows(IllegalArgumentException.class, () -> service.handle(null, null)).getMessage());
    }

    @Test
    void decodesTheBodyWithTheRequestCharsetAndDefaultsToUtf8() {
        // The original answered {a:'\u00e9'} with "Sum is: 0\u00e9.".
        assertEquals("Sum is: 0\u00e9.", service.handle("{a:'\u00e9'}".getBytes(ISO_8859_1), ISO_8859_1));
        assertEquals("Sum is: 0\u00e9.", service.handle("{a:'\u00e9'}".getBytes(UTF_8), null));
    }

    @ParameterizedTest(name = "[{index}] depth 1000")
    @MethodSource("deepestNesting")
    void acceptsNestingUpToTheBound(String body, String originalResponse) throws Throwable {
        assertEquals(originalResponse, onSmallStack(() -> service.handle(body.getBytes(UTF_8), UTF_8)));
    }

    @Test
    void failsOnIncrementsNestedToTheBoundAsTheOriginalDid() throws Throwable {
        // The original failed on this body with "SyntaxError: Invalid increment operand.".
        String body = "{a:" + "++[".repeat(999) + "1" + "]".repeat(999) + "}";

        IllegalArgumentException e = onSmallStack(
                () -> assertThrows(IllegalArgumentException.class, () -> service.sum(body)));

        assertTrue(e.getMessage().startsWith("SyntaxError: Invalid increment operand. (at offset "), e.getMessage());
    }

    @ParameterizedTest(name = "[{index}] depth 1001")
    @MethodSource("tooDeepNesting")
    void rejectsNestingBeyondTheBound(String body) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.sum(body));

        assertTrue(e.getMessage().startsWith("Input nests arrays and objects more than 1000 levels deep"),
                e.getMessage());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("longJoins")
    void joinsInheritedArraysOfAnyLengthAsTheOriginalDid(String body, String originalResponse) {
        String response = service.handle(body.getBytes(UTF_8), UTF_8);

        // Compared by length and first differing index, so a failure does not print 16 million characters.
        assertEquals(originalResponse.length(), response.length(), "response length");
        assertEquals(-1, Arrays.mismatch(originalResponse.toCharArray(), response.toCharArray()),
                "first index where the response differs from the original's");
    }

    @Test
    void failsFastWhenAJoinsSeparatorsAloneExceedTheLongestJavaString() {
        // An object with an array prototype and its own length joins length elements, here 2^32 - 1, whose 2^32 - 2
        // commas no Java string holds. The original appended commas until it failed: on a 256 MiB heap, with
        // java.lang.OutOfMemoryError: Java heap space, after under a second.
        String body = "{\"a\":{\"__proto__\":[],\"length\":4294967295}}";

        IllegalArgumentException e = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertThrows(IllegalArgumentException.class, () -> service.handle(body.getBytes(UTF_8), UTF_8)));

        assertEquals("Input builds a string longer than 2147483639 characters, more than a Java string can hold, so"
                + " the original failed as well", e.getMessage());
    }

    @Test
    void convertsJavaMapsAndListsAsTheOriginalConvertedObjectsAndArrays() {
        // The original answered {a:[{}]} with "Sum is: 0[object Object]." and {a:[1,null,"x",[2,3]]} with
        // "Sum is: 01,,x,2,3.", the sum's initial 0 followed by the string conversion of the value of a.
        assertEquals("[object Object]", AdditionService.jsToString(Map.of("a", 1)));
        assertEquals("1,,x,2,3", AdditionService.jsToString(Arrays.asList(1, null, "x", List.of(2, 3))));
    }

    @Test
    void failsToConvertAJavaMapWhoseToStringEntryShadowsTheMethod() {
        // The original failed on {a:[{toString:1}]}, whose element is converted to a string, with this TypeError.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AdditionService.jsToString(Map.of("toString", 1)));

        assertEquals("TypeError: Cannot find default value for object.", e.getMessage());
    }

    @Test
    void rejectsJavaValuesWithoutAJavaScriptCounterpart() {
        IllegalArgumentException conversion = assertThrows(IllegalArgumentException.class,
                () -> AdditionService.jsToString(new Object()));
        IllegalArgumentException rendering = assertThrows(IllegalArgumentException.class,
                () -> AdditionService.render(1));

        assertEquals("Unsupported value type: java.lang.Object", conversion.getMessage());
        assertEquals("Unsupported script result type: java.lang.Integer", rendering.getMessage());
    }

    /**
     * Runs {@code task} on a thread whose stack is a quarter of the JVM default, so the deepest nesting passes only
     * when nesting costs no thread stack.
     */
    private static <T> T onSmallStack(Callable<T> task) throws Throwable {
        FutureTask<T> result = new FutureTask<>(task);
        Thread thread = new Thread(null, result, "small-stack", SMALL_STACK_BYTES);
        thread.start();
        try {
            return result.get();
        } catch (ExecutionException e) {
            throw e.getCause();
        }
    }
}
