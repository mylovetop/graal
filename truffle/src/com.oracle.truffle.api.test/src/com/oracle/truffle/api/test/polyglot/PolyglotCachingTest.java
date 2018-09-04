/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 * 
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 * 
 * (a) the Software, and
 * 
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 * 
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 * 
 * This license is subject to the following condition:
 * 
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.test.polyglot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/*
 * Please note that any OOME exceptions when running this test indicate memory leaks in Truffle.
 */
public class PolyglotCachingTest {

    /*
     * Also used for other GC tests.
     */
    public static final int GC_TEST_ITERATIONS = 15;

    @Test
    public void testDisableCaching() throws Exception {
        AtomicInteger parseCalled = new AtomicInteger(0);
        ProxyLanguage.setDelegate(new ProxyLanguage() {
            @Override
            protected CallTarget parse(ParsingRequest request) throws Exception {
                parseCalled.incrementAndGet();
                return Truffle.getRuntime().createCallTarget(RootNode.createConstantNode(""));
            }
        });
        Context c = Context.create();
        Source cachedSource = Source.newBuilder(ProxyLanguage.ID, "testSourceInstanceIsEqual", "name").cached(true).build();
        Source uncachedSource = Source.newBuilder(ProxyLanguage.ID, "testSourceInstanceIsEqual", "name").cached(false).build();
        assertEquals(0, parseCalled.get());
        c.eval(uncachedSource);
        assertEquals(1, parseCalled.get());
        c.eval(uncachedSource);
        assertEquals(2, parseCalled.get());
        c.eval(uncachedSource);
        assertEquals(3, parseCalled.get());

        c.eval(cachedSource);
        assertEquals(4, parseCalled.get());
        c.eval(cachedSource);
        assertEquals(4, parseCalled.get());
    }

    /*
     * Tests that the outer source instance is never the same as the one passed in. That allows the
     * outer source instance to be collected while the inner one is still referenced strongly. The
     * garbage collection of the outer source instance will trigger cleanup the cached CallTargets.
     */
    @Test
    public void testLanguageSourceInstanceIsEqualToEmbedder() throws Exception {
        AtomicReference<com.oracle.truffle.api.source.Source> innerSource = new AtomicReference<>(null);
        ProxyLanguage.setDelegate(new ProxyLanguage() {
            @Override
            protected CallTarget parse(ParsingRequest request) throws Exception {
                innerSource.set(request.getSource());
                return Truffle.getRuntime().createCallTarget(RootNode.createConstantNode(""));
            }
        });
        Context c = Context.create();
        Source source = Source.create(ProxyLanguage.ID, "testSourceInstanceIsEqual");
        c.eval(source);

        assertNotNull(innerSource.get());
        Field f = Source.class.getDeclaredField("impl");
        f.setAccessible(true);

        assertEquals(f.get(source), innerSource.get());
        assertEquals(innerSource.get(), f.get(source));
        assertNotSame(innerSource.get(), f.get(source));
    }

    /*
     * Test that CallTargets stay cached as long as their source instance is alive.
     */
    @Test
    public void testParsedASTIsNotCollectedIfSourceIsAlive() {
        Assume.assumeFalse("This test is too slow in fastdebug.", System.getProperty("java.vm.version").contains("fastdebug"));
        setupTestLang();

        Context context = Context.create();
        Source source = Source.create(ProxyLanguage.ID, "0"); // needs to stay alive

        WeakReference<CallTarget> parsedRef = new WeakReference<>(assertParsedEval(context, source));
        for (int i = 0; i < GC_TEST_ITERATIONS; i++) {
            // cache should stay valid and never be collected as long as the source is alive.
            assertCachedEval(context, source);
            System.gc();
        }
        assertNotNull(parsedRef.get());
        context.close();
    }

    /*
     * Test that if the context is strongly referenced and the source reference is freed the GC can
     * collect the source together with the cached CallTargets.
     */
    @Test
    public void testSourceFreeContextStrong() {
        Assume.assumeFalse("This test is too slow in fastdebug.", System.getProperty("java.vm.version").contains("fastdebug"));
        setupTestLang();

        Context survivingContext = Context.create();
        assertObjectsCollectible(GC_TEST_ITERATIONS, (iteration) -> {
            Source source = Source.create(ProxyLanguage.ID, String.valueOf(iteration));
            CallTarget target = assertParsedEval(survivingContext, source);
            assertCachedEval(survivingContext, source);
            return target;
        });
        survivingContext.close();
    }

    /*
     * Test that if the context is freed and the source reference is still strong the GC can collect
     * the cached CallTargets.
     */
    @Test
    public void testSourceStrongContextFree() {
        Assume.assumeFalse("This test is too slow in fastdebug.", System.getProperty("java.vm.version").contains("fastdebug"));
        setupTestLang();

        List<Source> survivingSources = new ArrayList<>();

        assertObjectsCollectible(GC_TEST_ITERATIONS, (iteration) -> {
            Context context = Context.create();
            Source source = Source.create(ProxyLanguage.ID, String.valueOf(iteration));
            CallTarget parsedAST = assertParsedEval(context, source);
            assertCachedEval(context, source);
            survivingSources.add(source);
            context.close();
            return parsedAST;
        });
    }

    private static void assertObjectsCollectible(int iterations, Function<Integer, Object> objectFactory) {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        List<WeakReference<Object>> collectibleObjects = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            collectibleObjects.add(new WeakReference<>(objectFactory.apply(i), queue));
            System.gc();
        }
        int refsCleared = 0;
        while (queue.poll() != null) {
            refsCleared++;
        }
        // we need to have any refs cleared for this test to have any value
        Assert.assertTrue(refsCleared > 0);
    }

    long parseCount;
    CallTarget lastParsedTarget;

    private void setupTestLang() {
        byte[] bytes = new byte[16 * 1024 * 1024];
        byte byteValue = (byte) 'a';
        Arrays.fill(bytes, byteValue);
        String testString = new String(bytes); // big string

        ProxyLanguage.setDelegate(new ProxyLanguage() {
            @Override
            protected CallTarget parse(ParsingRequest request) throws Exception {
                int index = Integer.parseInt(request.getSource().getCharacters().toString());
                parseCount++;
                lastParsedTarget = Truffle.getRuntime().createCallTarget(new RootNode(languageInstance) {
                    /*
                     * Typical root nodes have a strong reference to source. We need to ensure that
                     * we can still collect the cache if that happens.
                     */
                    @SuppressWarnings("unused") final com.oracle.truffle.api.source.Source source = request.getSource();

                    @SuppressWarnings("unused") final String bigString = testString.substring(index, testString.length());

                    @Override
                    public Object execute(VirtualFrame frame) {
                        return "foobar";
                    }
                });
                return lastParsedTarget;
            }
        });
    }

    private CallTarget assertParsedEval(Context context, Source source) {
        this.parseCount = 0;
        assertEquals("foobar", context.eval(source).asString());
        assertEquals(1, this.parseCount);
        CallTarget parsed = this.lastParsedTarget;
        assertNotNull(parsed);
        this.lastParsedTarget = null;
        return parsed;
    }

    private void assertCachedEval(Context context, Source source) {
        this.parseCount = 0;
        this.lastParsedTarget = null;
        assertEquals("foobar", context.eval(source).asString());
        assertEquals(0, this.parseCount);
        assertNull(lastParsedTarget);
    }

}
