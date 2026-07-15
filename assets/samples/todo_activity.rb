require 'ruboto/widget'
require 'ruboto/toast'

ruboto_import_widgets :Button, :EditText, :LinearLayout, :ListView, :TextView

# A minimal Todo list app.
#
# * Type a todo and press "Add" to put it on the list.
# * Tap a todo to mark it as done (it is removed from the list).
# * Todos are saved to a file, so they survive restarting the app.
#
class TodoActivity
  def onCreate(bundle)
    super
    set_title 'Ruboto Todo'
    @todos = load_todos

    self.content_view =
        linear_layout orientation: :vertical do
          @new_todo = edit_text hint: 'What needs doing?',
                                layout: {width: :match_parent}
          button text: 'Add',
                 layout: {width: :match_parent},
                 on_click_listener: proc { add_todo }
          @hint = text_view text: 'Tap a todo to mark it done.',
                            padding: [20, 20, 20, 20],
                            gravity: :center
          @list = list_view list: @todos,
                            layout: {width: :match_parent, height: :match_parent},
                            on_item_click_listener: proc { |_parent, _view, position, _id| finish_todo(position) }
        end
    update_hint
  rescue Exception
    puts "Exception creating activity: #{$!}"
    puts $!.backtrace.join("\n")
  end

  private

  def add_todo
    text = @new_todo.text.to_s.strip
    if text.empty?
      toast 'Nothing to add'
      return
    end
    @todos << text
    @new_todo.text = ''
    save_todos
    @list.reload_list(@todos)
    update_hint
  end

  def finish_todo(position)
    done = @todos.delete_at(position)
    save_todos
    @list.reload_list(@todos)
    update_hint
    toast "Done: #{done}"
  end

  def update_hint
    @hint.text = @todos.empty? ? 'No todos.  Add one above!' : 'Tap a todo to mark it done.'
  end

  def todo_file
    File.join($application_context.files_dir.absolute_path, 'todos.txt')
  end

  def load_todos
    File.exist?(todo_file) ? File.readlines(todo_file, chomp: true).reject(&:empty?) : []
  end

  def save_todos
    File.write(todo_file, @todos.join("\n"))
  end
end
