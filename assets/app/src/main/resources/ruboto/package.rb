require 'ruboto/base'

$package = JavaUtilities.get_package_module_dot_format($package_name)
# R.attr is provided for all R classes by ruboto/base.rb.
R = $package.R

# FIXME(uwe):  DEPRECATED(2014-07-29):  Use R and R.id instead.
module Ruboto
  R = ::R
  begin
    Id = R.id
  rescue NameError
    Java::android.util.Log.d 'RUBOTO', 'no R$id'
  end
end
# EMXIF
