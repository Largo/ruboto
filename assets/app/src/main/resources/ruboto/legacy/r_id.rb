# Loaded on first use of Ruboto::Id (see ruboto/package.rb).
# FIXME(uwe):  DEPRECATED(2014-07-29):  Use R.id instead.
module Ruboto
  begin
    Id = R.id
  rescue NameError
    Java::android.util.Log.d 'RUBOTO', 'no R$id'
  end
end
# EMXIF
