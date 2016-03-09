package org.jduff;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SameFileTest {

    Path a = null;
    Path b = null;

    @Before
    public void setUp() throws Exception {
        a = Files.createTempFile(null, null);
        b = Files.createTempFile(null, null);
    }

    @After
    public void tearDown() throws Exception {
        Files.deleteIfExists(a);
        Files.deleteIfExists(b);
    }

    @Test
    public void test() throws IOException {
        assertFalse(a.equals(b));
        assertFalse(Files.isSameFile(a, b));

        Files.write(a, "hello world".getBytes());

        assertFalse(a.equals(b));
        assertFalse(Files.isSameFile(a, b));

        Files.delete(b);
        Files.createSymbolicLink(b, a);

        assertFalse(a.equals(b));
        assertTrue(Files.isSameFile(a, b));

        Files.delete(b);
        Files.createLink(b, a);

        assertFalse(a.equals(b));
        assertTrue(Files.isSameFile(a, b));
    }

}
