package common;

import lombok.extern.slf4j.Slf4j;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({
        ByteBufferAllocatorTest.class,
        KVByteBufferTest.class
})

@Slf4j
public class CommonSuiteTest {
}
