require 'fileutils'
require 'rbconfig'
require 'tmpdir'

# Regenerates assets/app/src/main/baseline-prof.txt, the baseline profile
# every generated app ships (see Ruboto::Util::Build#write_baseline_profile).
# It lists the JRuby methods and classes a Ruboto app executes from
# setUpJRuby to the end of preloadScript, so it has to be regenerated when
# the JRuby version or the boot path (JRubyAdapter, the jruby-core patches)
# changes.
#
# From a real Android 14 ART running in a chroot (the start-up investigation
# harness: sysimg34/chroot, tools/achroot.sh, tools/aenv.sh, tools/bcp.txt and
# the ArtBoot replay of JRubyAdapter in <chroot>/data/local/tmp/harness.jar):
#
#   rake baseline_profile ART_HARNESS=/root/workspace/ruboto-startup/apk-layout
#
# This generates org.ruboto.startup from this checkout, builds its benchmark
# APK (APK=<path> skips that), boots JRuby under ART with the JIT profile
# saver on, and converts the profile.
#
# From a device instead (not tried yet; any non-debuggable build of a freshly
# generated app, reset to 'verify' first so AOT code from an older profile
# does not hide methods from the profile saver; SIGUSR1 makes ART save the
# profile now and needs adb root, otherwise wait a minute or two for the
# periodic save):
#
#   adb shell cmd package compile -f -m verify org.ruboto.startup
#   adb shell am start -W -S -n org.ruboto.startup/.StartupActivity
#   sleep 10   # ART snapshots the startup classes and methods after 5 s
#   adb shell killall -s SIGUSR1 org.ruboto.startup && sleep 2
#   adb shell pm dump-profiles --dump-classes-and-methods org.ruboto.startup
#   adb pull /data/misc/profman/org.ruboto.startup-primary.prof.txt
#   rake baseline_profile PROFILE_DUMP=org.ruboto.startup-primary.prof.txt
module BaselineProfile
  extend Rake::FileUtilsExt
  TEMPLATE = File.expand_path('../assets/app/src/main/baseline-prof.txt', __dir__)
  PACKAGE = 'org.ruboto.startup' # ArtBoot replays StartupActivity of this package

  # Waits for ART's startup snapshot (resolved classes and startup methods,
  # taken 5 s after the runtime starts), then forces a save with SIGUSR1.
  # System.exit alone ends the process without the profile saver writing.
  PROFILE_BOOT = <<~JAVA
    public class ProfileBoot {
        public static void main(String[] args) throws Throwable {
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    try {
                        Thread.sleep(6000);
                        android.system.Os.kill(android.system.Os.getpid(), android.system.OsConstants.SIGUSR1);
                        Thread.sleep(1000);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
            Class.forName("org.ruboto.artboot.ArtBoot").getMethod("main", String[].class).invoke(null, (Object) args);
        }
    }
  JAVA

  module_function

  def build_apk(dir)
    root = File.expand_path('..', __dir__)
    app = File.join(dir, 'startup')
    sh RbConfig.ruby, "-I#{root}/lib", "#{root}/bin/ruboto", 'gen', 'app',
        '--package', PACKAGE, '--name', 'Startup', '--path', app
    Dir.chdir(app) { sh 'nice', '-n', '10', ENV['GRADLE'] || 'gradle', '--no-daemon', '-q', 'assembleBenchmark' }
    File.join(app, 'app/build/outputs/apk/benchmark/app-benchmark.apk')
  end

  def android_tool(glob)
    home = ENV['ANDROID_HOME'] || ENV['ANDROID_SDK_ROOT'] || abort('Set ANDROID_HOME.')
    versions = Dir[File.join(home, glob)].map { |f| [f[home.size..-1][/\d+(\.\d+)*/].split('.').map(&:to_i), f] }
    versions.max&.last || abort("#{glob} not found in #{home}")
  end

  def compile_profile_boot(dir)
    android_jar = android_tool('platforms/android-*/android.jar')
    File.write(File.join(dir, 'ProfileBoot.java'), PROFILE_BOOT)
    sh 'javac', '-nowarn', '--release', '17', '-cp', android_jar, '-d', dir, File.join(dir, 'ProfileBoot.java')
    sh android_tool('build-tools/*/d8'), '--min-api', '33', '--lib', android_jar,
        '--output', File.join(dir, 'profileboot.jar'), *Dir[File.join(dir, '*.class')]
    File.join(dir, 'profileboot.jar')
  end

  # Runs the boot `runs` times into one profile (the saver merges into an
  # existing file) and returns `profman --dump-classes-and-methods` output.
  def dump_from_art(harness, apk, runs, work)
    chroot = File.join(harness, 'sysimg34/chroot')
    abort "No ArtBoot harness in #{chroot}/data/local/tmp/harness.jar" unless File.exist?("#{chroot}/data/local/tmp/harness.jar")
    dev_dir = '/data/local/tmp/ruboto-baseline-profile'
    FileUtils.rm_rf(chroot + dev_dir)
    FileUtils.mkdir_p(chroot + dev_dir)
    dev_apk = "#{dev_dir}/base.apk"
    FileUtils.cp(apk, chroot + dev_apk)
    FileUtils.cp(compile_profile_boot(work), "#{chroot}#{dev_dir}/profileboot.jar")
    bcp = File.read(File.join(harness, 'tools/bcp.txt')).strip
    chroot_cmd = ". #{harness}/tools/aenv.sh && exec nice -n 10 #{harness}/tools/achroot.sh"
    # Verify at "install" like a device does.  Without a vdex the runtime
    # verifier loads about 1000 more classes, which would end up in the
    # profile and the app image.
    sh "#{harness}/tools/mkoat.sh #{dev_apk} verify > /dev/null"
    runs.times do |i|
      puts "ART boot #{i + 1}/#{runs}"
      # Without -Xps-inline-cache-threshold profman aborts dumping the JIT's
      # inline caches ("Tried to access vB of instruction nop").
      out = `#{chroot_cmd} /apex/com.android.art/bin/dalvikvm64 -Xbootclasspath:#{bcp} -Xbootclasspath-locations:#{bcp} \
             -Ximage:/system/framework/boot.art -Xmx512m -Xss1m -Xhidden-api-policy:disabled -Xuse-stderr-logger \
             -Xjitsaveprofilinginfo -Xps-profile-path:#{dev_dir}/boot.prof -Xps-hot-startup-method-samples:1 \
             -Xps-min-methods-to-save:1 -Xps-min-classes-to-save:1 -Xps-inline-cache-threshold:65535 \
             -cp #{dev_apk}:/data/local/tmp/harness.jar:#{dev_dir}/profileboot.jar ProfileBoot #{dev_apk} #{dev_dir}/data 2>&1`
      unless out.include?('PHASE preloadScript done') && !out.include?('PRELOAD FAILED')
        abort "JRuby boot under ART failed:\n#{out}"
      end
      puts out[/^PHASE preloadScript done.*/]
    end
    dump = `#{chroot_cmd} /apex/com.android.art/bin/profman --profile-file=#{dev_dir}/boot.prof \
            --apk=#{dev_apk} --dex-location=#{dev_apk} --dump-classes-and-methods`
    abort 'profman --dump-classes-and-methods failed' unless $?.success? && !dump.empty?
    FileUtils.rm_rf(chroot + dev_dir)
    dump
  end

  # Every executed method becomes a startup method (HSP): ART only marks
  # methods run before its 5 s snapshot as startup, and on a slow device the
  # boot can take longer than that.  Ruboto's own classes and the app's are
  # covered by wildcards, androidx libraries ship their own profiles, AGP's
  # rule parser takes no array classes, and inline caches ("+]...") are
  # profman's syntax, not baseline-profile rules.
  def convert(dump, source, jruby_version)
    rules = dump.each_line.map(&:strip).reject do |l|
      l.empty? || l.start_with?('#', '[') || l =~ %r{\A[HSP]*L(org/ruboto/|androidx/)}
    end
    rules = rules.map { |l| l.sub(/\A[HSP]+(?=L)/, 'HSP').sub(/\+\].*/, '') }.uniq.sort
    <<~HEADER + rules.join("\n") + "\n"
      # ART baseline profile for the JRuby #{jruby_version} boot of a Ruboto app.
      # Generated by `rake baseline_profile` in the Ruboto repo from #{source};
      # do not edit by hand.
      HSPLorg/ruboto/**->**(**)**
      Lorg/ruboto/**;
    HEADER
  end
end

desc 'Regenerate the baseline profile (assets/app/src/main/baseline-prof.txt); see rakelib/baseline_profile.rake'
task :baseline_profile do
  Dir.mktmpdir('ruboto-baseline-profile') do |work|
    if (dump_file = ENV['PROFILE_DUMP'])
      dump = File.read(dump_file)
      source = 'a device profile dump'
    else
      harness = ENV['ART_HARNESS'] || abort('Set ART_HARNESS (ART chroot harness dir) or PROFILE_DUMP (profman text dump).')
      apk = ENV['APK'] || BaselineProfile.build_apk(work)
      dump = BaselineProfile.dump_from_art(File.expand_path(harness), File.expand_path(apk),
                                           (ENV['RUNS'] || 3).to_i, work)
      source = 'Android 14 ART (dalvikvm in a chroot)'
    end
    jruby_version = ENV['JRUBY_VERSION'] || Dir["#{work}/startup/libs/jruby-core-*.jar"].first.to_s[/jruby-core-(.+)\.jar/, 1] || 'unknown'
    File.write(BaselineProfile::TEMPLATE, BaselineProfile.convert(dump, source, jruby_version))
    puts "Wrote #{File.readlines(BaselineProfile::TEMPLATE).size} lines to #{BaselineProfile::TEMPLATE}"
  end
end
