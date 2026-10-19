#######################################################
#
# ruboto/base.rb
#
# Code shared by other ruboto components.
#
#######################################################

require 'java'

# Create convenience method for top-level android package so we do not need to prefix with 'Java::'.
module Kernel
  def android
    Java::Android
  end

  alias :old_method_missing :method_missing
  def method_missing(method, *args, &block)
    if @ruboto_java_instance && @ruboto_java_instance.respond_to?(method)
      return @ruboto_java_instance.send(method, *args, &block)
    end
    old_method_missing(method, *args, &block)
  end
end

# R.attr resolves to Module#attr, because JRuby only adds a class method for
# a lower-case inner class when the name is not taken.  Defining "attr" for
# all Java classes covers android.R and the app's R whichever way they are
# reached (android.R, Java::Android::R, java_import 'android.R', $package.R),
# without creating the android.R proxy at start-up: that proxy eagerly builds
# proxies for all its inner classes and their thousands of constants.
class << Java::JavaLang::Object
  def attr(*args)
    if args.empty? && (java_class_name = java_class&.name) =~ /(\A|\.)R\z/
      return JavaUtilities.get_proxy_class("#{java_class_name}$attr")
    end
    super
  end
end

# FIXME(uwe):  DEPRECATED(2014-07-29):  Remove since we can access the value directly with "android.R.id", ie. "android.R.id.text1"
autoload :AndroidIds, 'ruboto/legacy/android_ids'
# EMXIF

module Ruboto
  CLASS_NAME_KEY = org.ruboto.ScriptInfo::CLASS_NAME_KEY
  SCRIPT_NAME_KEY = org.ruboto.ScriptInfo::SCRIPT_NAME_KEY
  THEME_KEY = org.ruboto.RubotoActivity::THEME_KEY
end
