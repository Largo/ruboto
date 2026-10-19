#######################################################
#
# ruboto/widget/constant_conversions.rb
#
# The rarely used part of the View symbol to constant
# conversions, loaded by ruboto/widget.rb on first use.
#
#######################################################

View.class_eval do
  # The full table as a Hash, built on the first call.  From then on it is
  # the store for the conversions, as before, so changes to it take effect.
  def self.constant_conversions
    table = (@convert_constants ||= {})
    constant_conversion_sources.each do |source|
      next if source.equal?(table)
      if source.is_a?(Hash)
        table.update(source)
      else
        klass, own_only = source
        names = own_only ? klass.constants - klass.superclass.constants : klass.constants
        names.each { |i| table[i.to_s.downcase.to_sym] = klass.const_get(i) }
      end
    end
    constant_conversion_sources.replace([table])
    table
  end

  # Conversions added one by one are Hash sources between the classes.
  def self.add_constant_conversion(from, to)
    symbol = from.to_s.downcase.to_sym
    found, previous = find_constant_conversion(symbol)
    if found
      puts "WARNING: Overwriting symbol to constant conversion for #{symbol.inspect}:  #{previous.inspect} => #{to.inspect}"
    end
    sources = constant_conversion_sources
    sources << {} unless sources.last.is_a?(Hash)
    sources.last[symbol] = to
  end

  # Without the cache of ruboto/widget.rb: the table returned by
  # constant_conversions can be changed directly.
  def self.convert_constant(from)
    return from unless from.is_a?(Symbol)
    resolve_constant_conversion(from)
  end

  def self.resolve_constant_conversion(symbol)
    found, value = find_constant_conversion(symbol)
    raise "Symbol #{symbol.inspect} doesn't have a corresponding View constant #{symbol.to_s.upcase}." unless found
    value
  end

  # Returns [true, value] or [false].  Table keys are downcased constant
  # names.  Tries the upcased name in all sources first, like
  # ruboto/widget.rb, then lists the constants of the classes for names that
  # are not all upper case (:scaletype for ImageView::ScaleType) or that
  # Object or its modules have as well.
  def self.find_constant_conversion(symbol)
    name = symbol.to_s
    constant_name = name.upcase
    fast = name == name.downcase && constant_name.match?(/\A[A-Z]\w*\z/) && !Object.const_defined?(constant_name)
    constant_conversion_sources.reverse_each do |source|
      if source.is_a?(Hash)
        return true, source[symbol] if source.include?(symbol)
      elsif fast
        klass, own_only = source
        if klass.const_defined?(constant_name) && !(own_only && klass.superclass.const_defined?(constant_name))
          return true, klass.const_get(constant_name)
        end
      end
    end
    constant_conversion_sources.reverse_each do |klass, own_only|
      next if klass.is_a?(Hash)
      names = own_only ? klass.constants - klass.superclass.constants : klass.constants
      constant = names.find { |i| i.to_s.downcase == name }
      return true, klass.const_get(constant) if constant
    end
    [false]
  end
end
