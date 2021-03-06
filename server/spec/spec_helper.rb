
ENV['APP_ROOT'] ||= File.expand_path(Dir.pwd)
ENV['JAVA_ROOT'] = File.join(ENV['APP_ROOT'],'java','server')
ENV['GAME_ENV'] = 'test'
require 'rubygems'

require 'java'
policyfile = File.join(ENV['APP_ROOT'],'config','app.policy')
java.lang.System.setProperty("java.security.policy", policyfile)

begin
  require 'game_machine'
rescue LoadError
  require_relative '../lib/game_machine'
end

java.lang.System.setSecurityManager(GameMachine::JavaLib::CodeblockSecurityManager.new)

RSpec.configure do |config|
  config.before(:suite) do
    GameMachine::Application.initialize!
  end

  config.before(:each) do
    GameMachine::AppConfig.instance.load_config
    GameMachine::Application.data_store
    GameMachine::Application.start_actor_system
    GameMachine::Application.start_core_systems
    GameMachine::Application.start_handlers
    #GameMachine::Application.start_game_systems
    
  end

  config.after(:each) do
    GameMachine::DbLib::Store.get_instance.shutdown
    GameMachine::Application.stop_actor_system
  end

  config.after(:suite) do
    puts "after suite"
  end
end

begin
  require_relative 'message_expectations'
rescue LoadError
end
