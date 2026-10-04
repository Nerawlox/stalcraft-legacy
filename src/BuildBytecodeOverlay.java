import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Build tool only. Reads class data; never initializes or executes game classes. */
@SuppressWarnings("deprecation")
public final class BuildBytecodeOverlay {
    public static void main(String[] args) throws Exception {
        final int[] changed = {0};
        byte[] input;
        try (ZipFile source = new ZipFile(args[0])) {
            input = readAll(source.getInputStream(source.getEntry("izmo.class")));
        }
        ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM4, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                if (name.equals("_l") && desc.equals("()Z")) {
                    MethodVisitor method = super.visitMethod(access, name, desc, sig, exceptions);
                    method.visitCode();
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, "LocalProfilerHooks", "isClientThread", "()Z");
                    method.visitInsn(Opcodes.IRETURN);
                    method.visitMaxs(1, 1);
                    method.visitEnd();
                    changed[0]++;
                    return null;
                }
                return super.visitMethod(access, name, desc, sig, exceptions);
            }
        }, 0);
        if (changed[0] != 1) throw new IllegalStateException("Profiler predicate not uniquely identified");
        Path output = Paths.get(args[1], "izmo.class");
        Files.write(output, writer.toByteArray());
        System.out.println("Replaced one client-thread profiler predicate");
        try (ZipFile source = new ZipFile(args[2])) {
            input = readAll(source.getInputStream(source.getEntry("net/minecraft/client/qlfw.class")));
        }
        reader = new ClassReader(input);
        final ClassWriter clientWriter = new ClassWriter(reader, 0);
        final int[] tickCalls = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM4, clientWriter) {
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, desc, sig, exceptions);
                if (!name.equals("_w") || !desc.equals("()V")) return original;
                return new MethodVisitor(Opcodes.ASM4, original) {
                    public void visitMethodInsn(int opcode, String owner, String name, String desc) {
                        if (owner.equals("local/stalcraft/OfflineHook") && name.equals("tick") && desc.equals("(Ljava/lang/Object;)V")) {
                            owner = "LocalClientProbe";
                            tickCalls[0]++;
                        }
                        super.visitMethodInsn(opcode, owner, name, desc);
                    }
                };
            }
        }, 0);
        if (tickCalls[0] != 1) throw new IllegalStateException("Offline tick call not uniquely identified");
        output = Paths.get(args[1], "net/minecraft/client/qlfw.class");
        Files.createDirectories(output.getParent());
        Files.write(output, clientWriter.toByteArray());
        System.out.println("Redirected one offline client tick call");
        replaceStaticMethod(args[0], args[1], "poersch/minecraft/bettergrassandleaves/renderer/BlockRendererList",
            "onEntityWalkingHook", "(Ltxrt;Llrzy;IIILxuac;)Z", null, null, null);
        replaceStaticMethod(args[2], args[1], "gloomyfolken/mods/asm/NettyHooks",
            "getServerIP", "()Ljava/lang/String;", "LocalClientProbe", "serverAddress", "()Ljava/lang/String;");
        try (ZipFile source = new ZipFile(args[0])) {
            input = readAll(source.getInputStream(source.getEntry("jlrg.class")));
        }
        reader = new ClassReader(input);
        final ClassWriter serverWriter = new ClassWriter(reader, 0);
        final int[] serverReturns = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM4, serverWriter) {
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, desc, sig, exceptions);
                if (!name.equals("_C") || !desc.equals("()V")) return original;
                return new MethodVisitor(Opcodes.ASM4, original) {
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "LocalServerProbe", "tick", "(Ljlrg;)V");
                            serverReturns[0]++;
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        if (serverReturns[0] != 1) throw new IllegalStateException("Server tick return not uniquely identified");
        Files.write(Paths.get(args[1], "jlrg.class"), serverWriter.toByteArray());
        bridgeHandler(args[2], args[1]);
        preserveScoreboardMetadata(args[0], args[1]);
        redirectChat(args[2], args[1]);
        redirectGameObjectLifecycle(args[2], args[1]);
    }

    private static void preserveScoreboardMetadata(String sourceJar, String outputDir) throws Exception {
        final String className = "rtfd";
        final String methodName = "_b";
        final String methodDesc = "(Laroe;)V";
        final String mapGetDesc = "(Ljava/lang/Object;)Ljava/lang/Object;";
        byte[] input;
        try (ZipFile source = new ZipFile(sourceJar)) {
            input = readAll(source.getInputStream(source.getEntry(className + ".class")));
        }
        ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, 0);
        final int[] calls = {0};
        final int[] captures = {0};
        final int[] merges = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, desc, sig, exceptions);
                if (name.equals("func_76184_a") && desc.equals("(Lrtag;)V")) {
                    return new MethodVisitor(Opcodes.ASM5, original) {
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "LocalScoreboardHooks", "captureOriginalData",
                                "(Lrtfd;Lrtag;)V", false);
                            captures[0]++;
                        }

                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(Math.max(maxStack, 2), maxLocals);
                        }
                    };
                }
                if (name.equals("func_76187_b") && desc.equals("(Lrtag;)V")) {
                    return new MethodVisitor(Opcodes.ASM5, original) {
                        private int returnCount;

                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.ARETURN) {
                                throw new IllegalStateException("Unexpected object return in scoreboard save method");
                            }
                            if (opcode == Opcodes.RETURN) {
                                returnCount++;
                            }
                            if (opcode == Opcodes.RETURN && returnCount == 2) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "LocalScoreboardHooks", "mergeOriginalData",
                                    "(Lrtfd;Lrtag;)V", false);
                                merges[0]++;
                            }
                            super.visitInsn(opcode);
                        }

                        public void visitMaxs(int maxStack, int maxLocals) {
                            if (returnCount != 2) throw new IllegalStateException("Expected early and final scoreboard save returns");
                            super.visitMaxs(Math.max(maxStack, 2), maxLocals);
                        }
                    };
                }
                if (!name.equals(methodName) || !desc.equals(methodDesc)) return original;
                return new MethodVisitor(Opcodes.ASM5, original) {
                    public void visitMethodInsn(int opcode, String owner, String callName, String callDesc, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEINTERFACE && isInterface &&
                                owner.equals("java/util/Map") && callName.equals("get") && callDesc.equals(mapGetDesc)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "LocalScoreboardHooks", "lookup",
                                "(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;", false);
                            super.visitInsn(Opcodes.NOP);
                            super.visitInsn(Opcodes.NOP);
                            calls[0]++;
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, callName, callDesc, isInterface);
                    }
                };
            }
        }, 0);
        if (calls[0] != 1) throw new IllegalStateException("Scoreboard criterion lookup not uniquely identified");
        if (captures[0] != 1) throw new IllegalStateException("Scoreboard NBT capture not uniquely identified");
        if (merges[0] != 1) throw new IllegalStateException("Scoreboard NBT merge at final save return not uniquely identified");
        Files.write(Paths.get(outputDir, className + ".class"), writer.toByteArray());
        System.out.println("Preserved unresolved criteria and unknown scoreboard NBT metadata");
    }

    private static void redirectGameObjectLifecycle(String sourceJar, String outputDir) throws Exception {
        byte[] input;
        final String className = "mods/gameobjects/world/GOWorldData";
        try (ZipFile source = new ZipFile(sourceJar)) {
            input = readAll(source.getInputStream(source.getEntry(className + ".class")));
        }
        ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, 0);
        final int[] beforeSpawn = {0};
        final int[] worldSet = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5, super.visitMethod(access, name, desc, sig, exceptions)) {
                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC && owner.equals("ServerPacketHandler")) {
                            if (name.equals("beforeSpawnGameObject") && desc.equals("(Lmods/gameobjects/world/GOWorldData;Lmods/gameobjects/world/GOInstance;)V")) {
                                owner = "LocalGameObjectHooks";
                                beforeSpawn[0]++;
                            } else if (name.equals("onWorldDataSet") && desc.equals("(Lmods/gameobjects/world/GOWorldData;Llrzy;)V")) {
                                owner = "LocalGameObjectHooks";
                                worldSet[0]++;
                            }
                        }
                        super.visitMethodInsn(opcode, owner, name, desc, itf);
                    }
                };
            }
        }, 0);
        if (beforeSpawn[0] != 1 || worldSet[0] != 1) throw new IllegalStateException("Game object lifecycle hooks not unique");
        Path output = Paths.get(outputDir, className + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static void redirectChat(String sourceJar, String outputDir) throws Exception {
        byte[] input;
        final String className = "mods/chat/client/screen/GuiChatActive";
        try (ZipFile source = new ZipFile(sourceJar)) {
            input = readAll(source.getInputStream(source.getEntry(className + ".class")));
        }
        ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, 0);
        final int[] calls = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5, super.visitMethod(access, name, desc, sig, exceptions)) {
                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
                        if (owner.equals("local/stalcraft/OfflineHook") && name.equals("sendLocalChat") && desc.equals("(Ljava/lang/Object;)V")) {
                            owner = "LocalChatHooks";
                            calls[0]++;
                        }
                        super.visitMethodInsn(opcode, owner, name, desc, isInterface);
                    }
                };
            }
        }, 0);
        if (calls[0] != 1) throw new IllegalStateException("Chat forwarding call not uniquely identified");
        Path output = Paths.get(outputDir, className + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static void bridgeHandler(String sourceJar, String outputDir) throws Exception {
        byte[] input;
        try (ZipFile source = new ZipFile(sourceJar)) {
            input = readAll(source.getInputStream(source.getEntry("ServerPacketHandler.class")));
        }
        ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, 0);
        final int[] changed = {0};
        final int[] statCalls = {0};
        final int[] invalidShotBroadcasts = {0};
        final String descriptor = "(Lgloomyfolken/bundle/common/core/dfaj;Lswfs;)V";
        reader.accept(new ClassVisitor(Opcodes.ASM4, writer) {
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                if (name.equals("handle") && desc.equals(descriptor)) {
                    MethodVisitor wrapper = super.visitMethod(access, name, desc, sig, exceptions);
                    wrapper.visitCode();
                    wrapper.visitVarInsn(Opcodes.ALOAD, 0);
                    wrapper.visitVarInsn(Opcodes.ALOAD, 1);
                    wrapper.visitMethodInsn(Opcodes.INVOKESTATIC, "LocalPacketBridge", "handle", desc);
                    wrapper.visitInsn(Opcodes.RETURN);
                    wrapper.visitMaxs(2, 2);
                    wrapper.visitEnd();
                    changed[0]++;
                    name = "reconstruction$handle";
                }
                final String originalName = name;
                final String originalDesc = desc;
                return new MethodVisitor(Opcodes.ASM4, super.visitMethod(access, name, desc, sig, exceptions)) {
                    public void visitMethodInsn(int opcode, String owner, String callName, String callDesc) {
                        if (originalName.equals("handleWeaponShoot") && originalDesc.equals("(Lrakn;Ljlas;)V") &&
                                opcode == Opcodes.INVOKESTATIC &&
                                owner.equals("cpw/mods/fml/common/network/PacketDispatcher") &&
                                callName.equals("sendPacketToAllInDimension") && callDesc.equals("(Lizjo;I)V")) {
                            owner = "LocalServerPlayerHooks";
                            callName = "discardInvalidShotBroadcast";
                            invalidShotBroadcasts[0]++;
                        }
                        if (owner.equals("gloomyfolken/mods/stalker/misc/qlfw") && callName.equals("_f") && callDesc.equals("()V")) {
                            if (!originalName.equals("refreshPlayerStats") && !originalName.equals("applyArmorToPlayer")) {
                                throw new IllegalStateException("Unexpected client stats invocation");
                            }
                            opcode = Opcodes.INVOKESTATIC;
                            owner = "LocalServerPlayerHooks";
                            callName = originalName.equals("applyArmorToPlayer") ? "armor" : "refresh";
                            callDesc = "(Lgloomyfolken/mods/stalker/misc/qlfw;)V";
                            statCalls[0]++;
                        }
                        super.visitMethodInsn(opcode, owner, callName, callDesc);
                    }
                };
            }
        }, 0);
        if (changed[0] != 1) throw new IllegalStateException("Packet handler not unique");
        if (statCalls[0] != 2) throw new IllegalStateException("Server stat invocations not uniquely identified");
        if (invalidShotBroadcasts[0] != 1) throw new IllegalStateException("Invalid shot broadcast not uniquely identified");
        Files.write(Paths.get(outputDir, "ServerPacketHandler.class"), writer.toByteArray());
        System.out.println("Redirected one invalid rakn broadcast to a no-op pending a firearm visual protocol");
        reader = new ClassReader(Files.readAllBytes(Paths.get(outputDir, "LocalPacketBridge.class")));
        final ClassWriter bridgeWriter = new ClassWriter(reader, 0);
        final int[] calls = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM4, bridgeWriter) {
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM4, super.visitMethod(access, name, desc, sig, exceptions)) {
                    public void visitMethodInsn(int opcode, String owner, String name, String desc) {
                        if (owner.equals("ServerPacketHandler") && name.equals("handle") && desc.equals(descriptor)) {
                            name = "reconstruction$handle";
                            calls[0]++;
                        }
                        super.visitMethodInsn(opcode, owner, name, desc);
                    }
                };
            }
        }, 0);
        if (calls[0] != 1) throw new IllegalStateException("Bridge delegation not unique");
        Files.write(Paths.get(outputDir, "LocalPacketBridge.class"), bridgeWriter.toByteArray());
    }

    private static void replaceStaticMethod(String sourceJar, String outputDir, String className,
        final String targetName, final String targetDesc, final String hookOwner, final String hookName, final String hookDesc) throws Exception {
        byte[] input;
        try (ZipFile source = new ZipFile(sourceJar)) {
            input = readAll(source.getInputStream(source.getEntry(className + ".class")));
        }
        ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, 0);
        final int[] matches = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM4, writer) {
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                if (!name.equals(targetName) || !desc.equals(targetDesc)) return super.visitMethod(access, name, desc, sig, exceptions);
                MethodVisitor method = super.visitMethod(access, name, desc, sig, exceptions);
                method.visitCode();
                if (hookOwner == null) {
                    // Walking particles belong to the client world; skip server worlds.
                    org.objectweb.asm.Label client = new org.objectweb.asm.Label();
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    method.visitFieldInsn(Opcodes.GETFIELD, "lrzy", "field_72995_K", "Z");
                    method.visitJumpInsn(Opcodes.IFNE, client);
                    method.visitInsn(Opcodes.ICONST_0);
                    method.visitInsn(Opcodes.IRETURN);
                    method.visitLabel(client);
                    // Original implementation is retained as a separately renamed method.
                    method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    for (int i = 2; i <= 4; ++i) method.visitVarInsn(Opcodes.ILOAD, i);
                    method.visitVarInsn(Opcodes.ALOAD, 5);
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, className, "reconstruction$clientWalking", targetDesc);
                    method.visitInsn(Opcodes.IRETURN);
                    method.visitMaxs(6, 6);
                    method.visitEnd();
                    matches[0]++;
                    return super.visitMethod(access, "reconstruction$clientWalking", desc, sig, exceptions);
                }
                method.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDesc);
                method.visitInsn(Opcodes.ARETURN);
                method.visitMaxs(1, 0);
                method.visitEnd();
                matches[0]++;
                return null;
            }
        }, 0);
        if (matches[0] != 1) throw new IllegalStateException("Target method not unique: " + className + "." + targetName);
        Path output = Paths.get(outputDir, className + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static byte[] readAll(java.io.InputStream stream) throws Exception {
        try (java.io.InputStream input = stream; java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
            return output.toByteArray();
        }
    }
}
