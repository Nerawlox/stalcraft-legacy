import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.swing.JOptionPane;

/** Self-contained launcher for a portable, already assembled Windows x64 kit. */
public final class PortableLauncher {
    private static Path root;
    private static Properties kit;
    private static final String[] COMMON = {
        "-Dfile.encoding=UTF-8", "-Dshow_globally_enabled=false", "-Dread_derived=true",
        "-DCustomNpcsSoundCache=true", "-Dload_dumped_event_classes=true", "-DusePrestitchedAtlas=true",
        "-Ddisable_item_atlas=true", "-Ddisable_mod_parsing=true", "-Duse_system_class_loader=true",
        "-Dfml.coreMods.load=codechicken.core.launch.CodeChickenCorePlugin"
    };

    public static void main(String[] args) {
        int code = 1;
        try {
            root = findRoot();
            kit = loadKit();
            code = dispatch(args);
        } catch (Exception e) {
            report("Ошибка: " + safeMessage(e));
            code = 1;
        }
        if (code != 0) System.exit(code);
    }

    private static Path findRoot() throws Exception {
        URL location = PortableLauncher.class.getProtectionDomain().getCodeSource().getLocation();
        Path jar = Paths.get(location.toURI()).toAbsolutePath().normalize();
        if (!jar.getFileName().toString().equalsIgnoreCase("launcher.jar"))
            throw new IOException("Код должен запускаться из app/launcher.jar");
        Path app = jar.getParent();
        if (app == null || app.getFileName() == null || !"app".equalsIgnoreCase(app.getFileName().toString()))
            throw new IOException("Не найдена папка app рядом с launcher.jar");
        Path base = app.getParent();
        if (base == null || Files.isSymbolicLink(base) || Files.isSymbolicLink(app) || Files.isSymbolicLink(jar))
            throw new IOException("Недопустимый путь комплекта");
        return base;
    }

    private static Properties loadKit() throws IOException {
        Path p = safeResolve("kit.properties");
        Properties props = new Properties();
        try (java.io.InputStream in = Files.newInputStream(p)) { props.load(in); }
        String role = props.getProperty("role", "");
        if (!role.equals("client") && !role.equals("server")) throw new IOException("kit.properties: role должен быть client или server");
        int port = parsePort(props.getProperty("port", "25576"));
        props.setProperty("port", Integer.toString(port));
        return props;
    }

    private static int dispatch(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Команда: client, server, check, stop или verify");
        String mode = args[0];
        Map<String,String> opts = options(args);
        if (mode.equals("verify")) {
            noExtra(opts, new String[0]);
            verify(false); System.out.println("Проверка комплекта пройдена."); return 0;
        }
        if (mode.equals("stop")) {
            requireRole("server"); noExtra(opts, new String[0]);
            verify(true);
            Path flag = safeResolve("game/reconstruction-stop.flag");
            atomicWrite(flag, "Orderly stop requested\n".getBytes(StandardCharsets.US_ASCII));
            System.out.println("Запрос остановки отправлен серверу этого комплекта."); return 0;
        }
        if (mode.equals("check")) {
            requireRole("client"); noExtra(opts, new String[] {"--host", "--port"});
            verify(true);
            Properties c = readProperties(safeResolve("connection.properties"));
            String host = opts.get("--host");
            if (host == null) host = ask("Адрес сервера для проверки:", c.getProperty("host", "127.0.0.1")).trim();
            int port = parsePort(opts.containsKey("--port") ? opts.get("--port") : c.getProperty("port", kit.getProperty("port")));
            validateHost(host);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), 5000);
                System.out.println("TCP-подключение доступно: " + host + ":" + port);
                return 0;
            } catch (IOException e) {
                System.out.println("TCP-подключение недоступно: " + host + ":" + port + " (" + safeMessage(e) + ")");
                return 2;
            }
        }
        if (!mode.equals("client") && !mode.equals("server")) throw new IllegalArgumentException("Неизвестная команда: " + mode);
        requireRole(mode);
        noExtra(opts, mode.equals("client") ? new String[] {"--host", "--username", "--port", "--probe-seconds", "--dry-run"} : new String[] {"--bind", "--port", "--probe-seconds", "--dry-run"});
        boolean dry = opts.containsKey("--dry-run");
        String probe = opts.get("--probe-seconds");
        int seconds = probe == null ? 0 : parseBounded(probe, 1, 300, "probe-seconds");
        verify(true);
        if (mode.equals("client")) launchClient(opts, seconds, dry);
        else launchServer(opts, seconds, dry);
        return 0;
    }

    private static void launchClient(Map<String,String> opts, int seconds, boolean dry) throws Exception {
        Path cfg = safeResolve("connection.properties");
        Properties c = readProperties(cfg);
        String host = opts.get("--host");
        String username = opts.get("--username");
        if (host == null) host = c.getProperty("host", "127.0.0.1");
        if (username == null) username = c.getProperty("username", "SecondPlayer");
        int port = parsePort(opts.containsKey("--port") ? opts.get("--port") : c.getProperty("port", kit.getProperty("port")));
        boolean prompt = !opts.containsKey("--host") || !opts.containsKey("--username");
        if (prompt) {
            String[] entered = askClient(host, username, port);
            host = entered[0]; username = entered[1]; port = parsePort(entered[2]);
        }
        validateHost(host); validateUsername(username);
        if (!dry) saveProperties(cfg, c, "host", host, "port", Integer.toString(port), "username", username);
        Path game = safeResolve("game");
        Path java = javaPath();
        Path cpRoot = safeResolve("app/classpath");
        List<Path> cp = Arrays.asList(safeResolve("app/overlay"), cpRoot.resolve("offline-patches.jar"),
                cpRoot.resolve("classes.jar"), cpRoot.resolve("libs.jar"), game.resolve("modassets"));
        Path natives = safeResolve("natives"), exbo = safeResolve("native-exbo");
        String nativePath = joinPaths(Arrays.asList(natives, java.getParent(), exbo, exbo.resolve("fmod")));
        List<String> cmd = new ArrayList<String>(); cmd.add(java.toString());
        cmd.addAll(Arrays.asList("-Xms256m", "-Xmx3g", "-Doffline.world=", "-Djava.library.path=" + nativePath));
        if (seconds > 0) { cmd.add("-Dreconstruction.clientProbe=true"); cmd.add("-Dreconstruction.clientSeconds=" + seconds); }
        cmd.addAll(Arrays.asList(COMMON)); cmd.add("-cp"); cmd.add(joinPaths(cp));
        cmd.addAll(Arrays.asList("net.minecraft.launchwrapper.Launch", "--version", "STALCRAFT-RECONSTRUCTION-2019",
                "--gameDir", ".", "--assetsDir", "assets", "--username", username, "--session", "0",
                "--width", "1280", "--height", "720", "--server", host, "--port", Integer.toString(port),
                "--tweakClass", "cpw.mods.fml.common.launcher.FMLTweaker"));
        Map<String,String> env = new LinkedHashMap<String,String>(System.getenv());
        env.put("PATH", nativePath + ";" + env.get("PATH"));
        run(modeLog("client"), game, cmd, env, dry, false, host, port);
    }

    private static void launchServer(Map<String,String> opts, int seconds, boolean dry) throws Exception {
        Path cfg = safeResolve("connection.properties");
        Properties c = readProperties(cfg);
        String bind = opts.get("--bind");
        if (bind == null) bind = c.getProperty("bind", "127.0.0.1");
        if (!opts.containsKey("--bind")) bind = askBind(bind);
        validateBind(bind);
        int port = parsePort(opts.containsKey("--port") ? opts.get("--port") : c.getProperty("port", kit.getProperty("port")));
        Path game = safeResolve("game");
        configureServer(game.resolve("server.properties"), bind, port, dry);
        if (!dry) saveProperties(cfg, c, "bind", bind, "port", Integer.toString(port));
        Path java = javaPath(); Path cpRoot = safeResolve("app/classpath");
        List<Path> cp = Arrays.asList(safeResolve("app/overlay"), cpRoot.resolve("server-bytecode-overlay.jar"),
                cpRoot.resolve("offline-patches.jar"), cpRoot.resolve("classes.jar"), cpRoot.resolve("libs.jar"), game.resolve("modassets"));
        List<String> cmd = new ArrayList<String>(); cmd.add(java.toString());
        cmd.addAll(Arrays.asList("-Xms256m", "-Xmx2g", "-Djava.awt.headless=true"));
        if (seconds > 0) { cmd.add("-Dreconstruction.serverProbe=true"); cmd.add("-Dreconstruction.stopAfterSeconds=" + seconds); }
        cmd.addAll(Arrays.asList(COMMON)); cmd.add("-cp"); cmd.add(joinPaths(cp));
        cmd.addAll(Arrays.asList("net.minecraft.launchwrapper.Launch", "--version", "STALCRAFT-RECONSTRUCTION-2019",
                "--gameDir", ".", "--assetsDir", "assets", "--tweakClass", "local.reconstruction.ServerTweaker"));
        if (!dry) Files.deleteIfExists(safeResolve("game/reconstruction-stop.flag"));
        run(modeLog("server"), game, cmd, null, dry, true, bind, port);
    }

    private static void run(Path log, Path cwd, List<String> cmd, Map<String,String> environment, boolean dry, final boolean server, final String endpoint, final int port) throws Exception {
        System.out.println("cwd=" + cwd);
        System.out.println("command=" + quote(cmd));
        if (dry) return;
        Files.createDirectories(log.getParent());
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.INHERIT);
        if (environment != null) pb.environment().putAll(environment);
        Process child = pb.start();
        System.out.println("Вывод процесса: " + log);
        final Process running = child;
        Thread output = new Thread(new Runnable() { public void run() {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8));
                 java.io.BufferedWriter writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line); writer.newLine(); writer.flush(); System.out.println(line);
                    if (server && line.contains("Done (")) System.out.println("Сервер готов: " + endpoint + ":" + port);
                }
            } catch (IOException ex) { System.err.println("Ошибка чтения вывода процесса: " + safeMessage(ex)); }
        }}, "portable-launcher-output");
        output.setDaemon(true); output.start();
        int exit = child.waitFor(); output.join();
        System.out.println("Процесс завершён, код=" + exit);
        if (exit != 0) throw new IOException("Дочерний процесс завершился с кодом " + exit + "; см. " + log);
    }

    private static Path modeLog(String mode) throws IOException { return safeResolve("logs/" + mode + "-" + System.currentTimeMillis() + ".log"); }
    private static Path javaPath() throws IOException { return safeResolve("runtime/java/bin/java.exe"); }

    private static void verify(boolean criticalOnly) throws Exception {
        Path mf = safeResolve("files.sha256");
        Set<String> listed = new HashSet<String>();
        Set<Path> checkedAncestors = new HashSet<Path>();
        checkedAncestors.add(root);
        List<String> lines = Files.readAllLines(mf, StandardCharsets.UTF_8);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        int checked = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            if (line.length() < 67 || !line.substring(64, 66).equals("  ") || !line.substring(0,64).matches("[0-9a-fA-F]{64}"))
                throw new IOException("Неверная строка files.sha256");
            String rel = line.substring(66);
            if (rel.indexOf('\\') >= 0 || rel.startsWith("/") || rel.matches("^[A-Za-z]:.*")) throw new IOException("Недопустимый путь в manifest: " + rel);
            if (!listed.add(rel)) throw new IOException("Повторный путь в files.sha256: " + rel);
            if (criticalOnly && !critical(rel)) { resolveLexically(rel); continue; }
            Path file = safeResolve(rel, checkedAncestors);
            if (!Files.isRegularFile(file)) throw new IOException("Нет файла из manifest: " + rel);
            byte[] actual = digestFile(md, file);
            if (!hex(actual).equalsIgnoreCase(line.substring(0,64))) throw new IOException("SHA-256 не совпадает: " + rel);
            checked++;
            if (!criticalOnly && checked % 500 == 0) System.out.println("Проверено файлов: " + checked + "/" + lines.size());
        }
        String[] required = {"runtime/java/bin/java.exe", "app/launcher.jar", "app/overlay", "app/classpath/offline-patches.jar",
                "app/classpath/classes.jar", "app/classpath/libs.jar", "natives", "native-exbo"};
        for (String r : required) {
            if (r.endsWith("/") || r.equals("app/overlay") || r.equals("natives") || r.equals("native-exbo")) {
                String prefix = r + "/"; boolean found = false;
                for (String path : listed) if (path.startsWith(prefix)) { found = true; break; }
                if (!found) throw new IOException("В manifest отсутствуют файлы " + r);
            } else if (!listed.contains(r)) throw new IOException("В manifest отсутствует " + r);
        }
        if ("server".equals(kit.getProperty("role")) && !listed.contains("app/classpath/server-bytecode-overlay.jar"))
            throw new IOException("В manifest отсутствует app/classpath/server-bytecode-overlay.jar");
        if (!criticalOnly) {
            // Full verification is the same complete payload manifest; mutable game state is deliberately unlisted.
            System.out.println("Проверены все " + listed.size() + " файлов.");
        }
    }

    private static byte[] digestFile(MessageDigest md, Path file) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) != -1) if (n > 0) md.update(buffer, 0, n);
            return md.digest();
        } finally { md.reset(); }
    }

    private static boolean critical(String p) {
        return p.startsWith("runtime/") || p.startsWith("app/") || p.startsWith("natives/") || p.startsWith("native-exbo/");
    }

    private static Path safeResolve(String relative) throws IOException {
        return safeResolve(relative, new HashSet<Path>());
    }

    private static Path safeResolve(String relative, Set<Path> checkedAncestors) throws IOException {
        Path p = resolveLexically(relative);
        Path cur = root;
        Path rel = root.relativize(p);
        for (Path part : rel) {
            cur = cur.resolve(part);
            if (checkedAncestors.add(cur) && Files.isSymbolicLink(cur)) throw new IOException("Символическая ссылка запрещена: " + relative);
        }
        return p;
    }

    private static Path resolveLexically(String relative) throws IOException {
        Path p = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!p.startsWith(root)) throw new IOException("Путь выходит за корень комплекта: " + relative);
        return p;
    }

    private static Map<String,String> options(String[] args) {
        Map<String,String> out = new LinkedHashMap<String,String>();
        for (int i=1;i<args.length;i++) {
            String a=args[i]; if (a.equals("--dry-run")) { if (out.put(a, "") != null) throw new IllegalArgumentException("Повторный аргумент " + a); continue; }
            if (!a.startsWith("--") || i+1>=args.length || args[i+1].startsWith("--")) throw new IllegalArgumentException("Ожидался аргумент --name value: " + a);
            if (out.put(a,args[++i]) != null) throw new IllegalArgumentException("Повторный аргумент " + a);
        }
        return out;
    }
    private static void noExtra(Map<String,String> opts, String[] allowed) { Set<String> s=new HashSet<String>(Arrays.asList(allowed)); for(String k:opts.keySet()) if(!s.contains(k)) throw new IllegalArgumentException("Неизвестный аргумент: " + k); }
    private static void requireRole(String expected) { if (!kit.getProperty("role").equals(expected)) throw new IllegalArgumentException("Команда доступна только комплекту role=" + expected); }

    private static String[] askClient(String host, String username, int port) throws IOException {
        host=ask("Адрес сервера:",host).trim();
        username=ask("Имя игрока:",username).trim();
        String enteredPort=ask("Порт сервера:",Integer.toString(port)).trim();
        return new String[] {host,username,enteredPort};
    }
    private static String askBind(String bind) throws IOException {
        List<String> addresses=localIPv4Choices();
        if(!GraphicsEnvironment.isHeadless()&&!addresses.isEmpty()) {
            Object selected=JOptionPane.showInputDialog(null,"Выберите локальный IPv4 адрес сервера. Для подключения извне используйте VPN, например Radmin VPN.","STALCRAFT Legacy",JOptionPane.QUESTION_MESSAGE,null,addresses.toArray(new String[addresses.size()]),bind);
            if(selected==null) throw new IOException("Ввод отменён"); return selected.toString().trim().split(" — ")[0];
        }
        return ask("IP-адрес, на котором будет доступен сервер:",bind).trim();
    }
    private static String ask(String message, String initial) throws IOException {
        if (!GraphicsEnvironment.isHeadless()) { String s=(String)JOptionPane.showInputDialog(null,message,"STALCRAFT Legacy",JOptionPane.QUESTION_MESSAGE,null,null,initial); if(s==null) throw new IOException("Ввод отменён"); return s; }
        Console con=System.console(); if(con!=null) { String s=con.readLine("%s [%s]: ",message,initial); return s==null||s.trim().isEmpty()?initial:s; }
        System.out.println(message+" ["+initial+"]"); BufferedReader br=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)); String s=br.readLine(); return s==null||s.trim().isEmpty()?initial:s;
    }

    private static void validateUsername(String name) { if(name==null||!name.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Имя игрока: 1–16 символов ASCII, буквы, цифры и _"); }
    private static void validateHost(String host) throws Exception {
        if(host==null||host.length()>253||!host.matches("[A-Za-z0-9.-]+")||host.startsWith(".")||host.endsWith(".")) throw new IllegalArgumentException("Недопустимый адрес сервера");
        if(host.matches("[0-9.]+")) { if (!(InetAddress.getByName(host) instanceof Inet4Address)) throw new IllegalArgumentException("Нужен IPv4 адрес"); }
    }
    private static void validateBind(String ip) throws Exception {
        if(ip==null||!ip.matches("[0-9.]+")) throw new IllegalArgumentException("Bind должен быть IPv4 адресом");
        InetAddress a=InetAddress.getByName(ip); if(!(a instanceof Inet4Address)) throw new IllegalArgumentException("Нужен IPv4 адрес");
        byte[] b=a.getAddress(); int x=b[0]&255,y=b[1]&255;
        boolean radmin=(x==26)&&isRadminAddress(ip);
        boolean allowed=(x==127)||(x==10)||(x==172&&y>=16&&y<=31)||(x==192&&y==168)||(x==100&&y>=64&&y<=127)||radmin;
        if(!allowed||ip.equals("0.0.0.0")) throw new IllegalArgumentException("Разрешены только loopback, частные IPv4 и CGNAT 100.64/10");
        if(x==26&&!isRadminAddress(ip)) throw new IllegalArgumentException("Сеть 26/8 разрешена только на интерфейсе Radmin VPN");
        if(!isAssignedLocal(ip)) throw new IllegalArgumentException("Адрес " + ip + " не назначен локальному сетевому интерфейсу этого компьютера");
    }
    private static boolean isAssignedLocal(String ip) throws Exception {
        InetAddress wanted=InetAddress.getByName(ip); java.util.Enumeration<NetworkInterface> it=NetworkInterface.getNetworkInterfaces();
        while(it!=null&&it.hasMoreElements()){NetworkInterface n=it.nextElement();java.util.Enumeration<InetAddress> as=n.getInetAddresses();while(as.hasMoreElements())if(wanted.equals(as.nextElement()))return true;} return false;
    }
    private static boolean isRadminAddress(String ip) throws Exception {
        InetAddress wanted=InetAddress.getByName(ip); java.util.Enumeration<NetworkInterface> it=NetworkInterface.getNetworkInterfaces();
        while(it!=null&&it.hasMoreElements()){NetworkInterface n=it.nextElement();String name=(n.getName()+" "+n.getDisplayName()).toLowerCase(Locale.ROOT);if(name.contains("radmin")){java.util.Enumeration<InetAddress> as=n.getInetAddresses();while(as.hasMoreElements())if(wanted.equals(as.nextElement()))return true;}} return false;
    }
    private static List<String> localIPv4Choices() {
        List<String> choices=new ArrayList<String>(); try {java.util.Enumeration<NetworkInterface> it=NetworkInterface.getNetworkInterfaces();while(it!=null&&it.hasMoreElements()){NetworkInterface n=it.nextElement();java.util.Enumeration<InetAddress> as=n.getInetAddresses();while(as.hasMoreElements()){InetAddress a=as.nextElement();if(a instanceof Inet4Address&&!a.isAnyLocalAddress()&&!a.isLoopbackAddress())choices.add(a.getHostAddress()+" — "+n.getDisplayName());}}}catch(Exception ignored){} choices.add(0,"127.0.0.1 — Loopback");return choices;
    }
    private static int parsePort(String s) { return parseBounded(s,1,65535,"port"); }
    private static int parseBounded(String s,int min,int max,String what) { try { int n=Integer.parseInt(s); if(n<min||n>max) throw new NumberFormatException(); return n; } catch(Exception e) { throw new IllegalArgumentException("Недопустимое значение " + what); } }

    private static void configureServer(Path file, String bind, int port, boolean dry) throws Exception {
        byte[] old=Files.readAllBytes(file); String text=new String(old,StandardCharsets.UTF_8); String[] lines=text.split("(?<=\\n)",-1);
        StringBuilder out=new StringBuilder(); boolean hasIp=false,hasPort=false;
        for(String line:lines) {
            String ending=line.endsWith("\r\n")?"\r\n":line.endsWith("\n")?"\n":"";
            String body=ending.isEmpty()?line:line.substring(0,line.length()-ending.length());
            if(body.startsWith("server-ip=")) { out.append("server-ip=").append(bind).append(ending); hasIp=true; }
            else if(body.startsWith("server-port=")) { out.append("server-port=").append(port).append(ending); hasPort=true; }
            else out.append(line);
        }
        if(!hasIp) out.append("\nserver-ip=").append(bind);
        if(!hasPort) out.append("\nserver-port=").append(port);
        if(dry) return;
        Path backup=file.resolveSibling("server.properties.initial.bak");
        if(!Files.exists(backup)) Files.copy(file,backup);
        atomicWrite(file,out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Properties readProperties(Path p) throws IOException { Properties x=new Properties(); if(Files.exists(p)) try(java.io.InputStream in=Files.newInputStream(p)){x.load(in);} return x; }
    private static void saveProperties(Path path, Properties old, String... kv) throws Exception {
        Properties p=new Properties(); p.putAll(old); for(int i=0;i<kv.length;i+=2)p.setProperty(kv[i],kv[i+1]);
        ByteArrayOutputStream b=new ByteArrayOutputStream(); p.store(b,"Portable launcher settings"); atomicWrite(path,b.toByteArray());
    }
    private static void atomicWrite(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent()); Path temp=path.resolveSibling(path.getFileName().toString()+".tmp");
        if(Files.isSymbolicLink(path)||Files.isSymbolicLink(temp)) throw new IOException("Запись через символическую ссылку запрещена: " + path.getFileName());
        Files.write(temp,bytes); try { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch(java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING); }
    }
    private static String joinPaths(List<Path> paths) { List<String> p=new ArrayList<String>(); for(Path x:paths)p.add(x.toString()); return join(p); }
    private static String join(List<String> parts) { StringBuilder b=new StringBuilder(); for(String s:parts){if(b.length()>0)b.append(';');b.append(s);} return b.toString(); }
    private static String quote(List<String> cmd) { StringBuilder b=new StringBuilder(); for(String s:cmd){if(b.length()>0)b.append(' ');b.append('"').append(s.replace("\"","\\\"")).append('"');} return b.toString(); }
    private static String hex(byte[] b) { StringBuilder s=new StringBuilder(); for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255)); return s.toString(); }
    private static String safeMessage(Exception e) { return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(); }
    private static void report(String s) { System.err.println(s); if(!GraphicsEnvironment.isHeadless()) JOptionPane.showMessageDialog(null,s,"STALCRAFT Legacy",JOptionPane.ERROR_MESSAGE); }
}
