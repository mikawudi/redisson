/**
 * Copyright (c) 2013-2019 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.codec;

import java.io.IOException;
import java.lang.reflect.Field;

import org.nustaq.serialization.FSTConfiguration;
import org.nustaq.serialization.FSTDecoder;
import org.nustaq.serialization.FSTEncoder;
import org.nustaq.serialization.FSTObjectInput;
import org.nustaq.serialization.FSTObjectOutput;
import org.nustaq.serialization.coders.FSTStreamDecoder;
import org.nustaq.serialization.coders.FSTStreamEncoder;
import org.redisson.client.codec.BaseCodec;
import org.redisson.client.handler.State;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;

/**
 * Efficient and speedy serialization codec fully
 * compatible with JDK Serialization codec.
 *
 * https://github.com/RuedigerMoeller/fast-serialization
 *
 * @author Nikita Koksharov
 *
 */
public class FstCodec extends BaseCodec {

    static class FSTDefaultStreamCoderFactory implements FSTConfiguration.StreamCoderFactory {

        Field chBufField;
        Field ascStringCacheField;

        {
            try {
                chBufField = FSTStreamDecoder.class.getDeclaredField("chBufS");
                ascStringCacheField = FSTStreamDecoder.class.getDeclaredField("ascStringCache");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            ascStringCacheField.setAccessible(true);
            chBufField.setAccessible(true);
        }
        
        private FSTConfiguration fstConfiguration;

        FSTDefaultStreamCoderFactory(FSTConfiguration fstConfiguration) {
            this.fstConfiguration = fstConfiguration;
        }

        @Override
        public FSTEncoder createStreamEncoder() {
            return new FSTStreamEncoder(fstConfiguration);
        }

        @Override
        public FSTDecoder createStreamDecoder() {
            return new FSTStreamDecoder(fstConfiguration) {
                public String readStringUTF() throws IOException {
                    try {
                        String res = super.readStringUTF();
                        chBufField.set(this, null);
                        return res;
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                }
                
                @Override
                public String readStringAsc() throws IOException {
                    try {
                        String res = super.readStringAsc();
                        ascStringCacheField.set(this, null);
                        return res;
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                }
            };
        }

        static ThreadLocal input = new ThreadLocal();
        static ThreadLocal output = new ThreadLocal();

        @Override
        public ThreadLocal getInput() {
            return input;
        }

        @Override
        public ThreadLocal getOutput() {
            return output;
        }

    }
    
    private final FSTConfiguration config;

    public FstCodec() {
        this(FSTConfiguration.createDefaultConfiguration());
    }
    
    public FstCodec(ClassLoader classLoader) {
        this(createConfig(classLoader));
    }
    
    public FstCodec(ClassLoader classLoader, FstCodec codec) {
        this(copy(classLoader, codec));
    }

    private static FSTConfiguration copy(ClassLoader classLoader, FstCodec codec) {
        FSTConfiguration def = FSTConfiguration.createDefaultConfiguration();
        def.setClassLoader(classLoader);
        def.setCoderSpecific(codec.config.getCoderSpecific());
        def.setCrossPlatform(codec.config.isCrossPlatform());
        def.setForceClzInit(codec.config.isForceClzInit());
        def.setForceSerializable(codec.config.isForceSerializable());
        def.setInstantiator(codec.config.getInstantiator(null));
        def.setName(codec.config.getName());
        def.setPreferSpeed(codec.config.isPreferSpeed());
        def.setShareReferences(codec.config.isShareReferences());
        def.setStreamCoderFactory(codec.config.getStreamCoderFactory());
        def.setVerifier(codec.config.getVerifier());
        return def;
    }
    
    private static FSTConfiguration createConfig(ClassLoader classLoader) {
        FSTConfiguration def = FSTConfiguration.createDefaultConfiguration();
        def.setClassLoader(classLoader);
        return def;
    }

    public FstCodec(FSTConfiguration fstConfiguration) {
        config = fstConfiguration;
        config.setStreamCoderFactory(new FSTDefaultStreamCoderFactory(config));
    }

    private final Decoder<Object> decoder = new Decoder<Object>() {
        @Override
        public Object decode(ByteBuf buf, State state) throws IOException {
            ByteBufInputStream in = new ByteBufInputStream(buf);
            FSTObjectInput inputStream = config.getObjectInput(in);
            try {
                return inputStream.readObject();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
//            } finally {
//                inputStream.resetForReuseUseArray(empty);
            }
        }
    };

    private final Encoder encoder = new Encoder() {

        @Override
        public ByteBuf encode(Object in) throws IOException {
            ByteBuf out = ByteBufAllocator.DEFAULT.buffer();
            ByteBufOutputStream os = new ByteBufOutputStream(out);
            FSTObjectOutput oos = config.getObjectOutput(os);
            try {
                oos.writeObject(in);
                oos.flush();
                return os.buffer();
            } catch (IOException e) {
                out.release();
                throw e;
            } catch (Exception e) {
                out.release();
                throw new IOException(e);
//            } finally {
//                oos.resetForReUse(empty);
            }
        }
    };

    @Override
    public Decoder<Object> getValueDecoder() {
        return decoder;
    }

    @Override
    public Encoder getValueEncoder() {
        return encoder;
    }
    
    @Override
    public ClassLoader getClassLoader() {
        if (config.getClassLoader() != null) {
            return config.getClassLoader();
        }

        return super.getClassLoader();
    }

}
