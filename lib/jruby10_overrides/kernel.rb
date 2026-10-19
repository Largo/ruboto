# This file boots the Ruby-based parts of JRuby.
#
# Ruboto: replaces jruby/kernel.rb in the jruby-core jar (see
# patch_jruby_core_for_android in lib/ruboto/util/update.rb, which only does so
# while the jar's file matches the JRuby 10.1 original this is based on).
# process_manager.rb and pathname.rb are loaded on first use instead of at
# every boot; everything else is unchanged.

module JRuby
  autoload :ProcessUtil, 'jruby/kernel/jruby/process_util.rb'
  autoload :Type, 'jruby/kernel/jruby/type.rb'
end

# Ruboto: process_manager.rb java_imports ProcessBuilder and ShellLauncher,
# which builds 15 Java proxy classes.  It is loaded on the first backtick;
# its own Kernel#` then replaces the one below.  ProcessBuilder exists on
# every Android version, so the original's fallback is not needed.
unless JRuby::Util.native_posix? # native POSIX uses new logic for back-quote
  module JRuby
    autoload :ProcessManager, 'uri:classloader:jruby/kernel/jruby/process_manager.rb'
  end

  module Kernel
    module_function

    def `(command)
      # ProcessManager calls JRuby.runtime, which only exists once the jruby
      # library is loaded.  Without this every backtick raises NoMethodError
      # when native POSIX is disabled, as on Android.
      require 'jruby'
      JRuby::ProcessManager.`(command)
    end
  end
end

# These are loads so they don't pollute LOADED_FEATURES
load 'uri:classloader:jruby/kernel/signal.rb'
load 'uri:classloader:jruby/kernel/kernel.rb'
load 'uri:classloader:jruby/kernel/proc.rb'
load 'uri:classloader:jruby/kernel/process.rb'
load 'uri:classloader:jruby/kernel/enumerator.rb'
load 'uri:classloader:jruby/kernel/enumerable.rb'
load 'uri:classloader:jruby/kernel/io.rb'
load 'uri:classloader:jruby/kernel/gc.rb'
load 'uri:classloader:jruby/kernel/range.rb'
load 'uri:classloader:jruby/kernel/file.rb'
load 'uri:classloader:jruby/kernel/method.rb'
load 'uri:classloader:jruby/kernel/thread.rb'
load 'uri:classloader:jruby/kernel/integer.rb'
load 'uri:classloader:jruby/kernel/time.rb'
load 'uri:classloader:jruby/kernel/string.rb'

# Ruboto: Pathname (pathname.rb and the PathnameLibrary extension) is loaded
# on first use of the constant or of Kernel#Pathname.  require 'pathname'
# stays a no-op, because Ruby.java already provides "pathname.rb".
autoload :Pathname, 'uri:classloader:jruby/kernel/pathname.rb'

module Kernel
  module_function

  def Pathname(path)
    # Loading pathname.rb defines the real Kernel#Pathname in place of this
    # one; check that it did instead of calling this method forever.
    stub = Kernel.instance_method(:Pathname)
    ::Pathname
    raise NotImplementedError, 'pathname.rb did not define Kernel#Pathname' if Kernel.instance_method(:Pathname) == stub

    Pathname(path)
  end
end
