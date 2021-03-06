module GameMachine
  module Actor

    class DuplicateHashringError < StandardError;end
    class MissingHashringError < StandardError;end

    # @abstract All game actors inherit fromm this class
    class Base < JavaLib::GameActor
  
      java_import 'org.slf4j.Logger'
      java_import 'org.slf4j.LoggerFactory'

      ON_RECEIVE_HOOKS = {}

      @@player_controller = nil

      class << self
        alias_method :apply, :new
        alias_method :create, :new


        def logger
          @logger ||= LoggerFactory.getLogger(self.name)
        end

        # Sets the system wide player controller class.
        # When a player logs in, a player controller with this class
        # will be created. The system notifies the player controller when
        # various player lifecycle events happen.
        #
        # This should only be called on subclasses, never on the Actor base
        # class
        def set_player_controller
          @@player_controller = self
          GameMachine.logger.info("Player controller set to #{self.name}")
        end

        def player_controller
          @@player_controller
        end


        def aspects
          @aspects ||= []
        end
        
        # Sets the message types that this actor knows about. Can be called
        # multiple times.  If passed an array of more then one message type,
        # both message types will need to be present on an entity before the
        # system will route the entity to the actor.
        #
        # messages will be routed to actors based on the aspects it has
        def aspect(new_aspects)
          aspects << new_aspects
          unless Application.registered.include?(self)
            Application.register(self)
          end
        end

        def hashring(name)
          JavaLib::Hashring.get_hashring(name)
        end

        # Find a local actor by name
        # @return [Actor::Ref]
        def find(name=self.name)
          Actor::Ref.new(local_path(name),name)
        end

        # find using fully qualified address, ie akka://cluster@ ...
        def find_by_address(address,name=self.name)
          path = "#{address}#{local_path(name)}"
          Actor::Ref.new(path,name)
        end

        # Find a remote actor by name
        # @return [Actor::Ref]
        def find_remote(server,name=self.name)
          Actor::Ref.new(remote_path(server,name),name)
        end

        # Returns a local actor ref from the distributed ring of actors based
        # on a consistent hashing of the id.
        # @return [Actor::Ref]
        def find_distributed_local(id,name=self.name)
          Actor::Ref.new(local_distributed_path(id, name),name)
        end

        # Returns an actor ref from the distributed ring of actors based
        # on a consistent hashing of the id. The actor returned can be from
        # any server in the cluster
        # @return [Actor::Ref]
        def find_distributed(id,name=self.name)
          Actor::Ref.new(distributed_path(id, name),name)
        end

        def local_path(name)
          "/user/#{name}"
        end

        def model_filter(message)
          if message.is_a?(MessageLib::Entity) && message.has_json_entity
            # Don't convert outgoing messages
            if message.send_to_player
              message
            else
              model = Model.from_entity(message)
              if message.has_player
                model.player_id = message.player.id
              end
              return model
            end
          end
          message
        end

        private

        def remote_path(server,name)
          "#{server}/user/#{name}"
        end

        def local_distributed_path(id,name)
          node = hashring(name).node_for(id)
          "/user/#{node}"
        end

        def distributed_path(id,name)
          server = Akka.instance.hashring.node_for(id)
          node = hashring(name).node_for(id)
          "#{server}/user/#{node}"
        end

      end

      # This indirection is primarily because Akka's test actors
      # hide onReceive, so in tests we need to call receive_message
      def receive_message(message)
        message = self.class.model_filter(message)
        on_receive(message)
      end

      # So we can hook into message passing for our own filters and the like
      def onReceive(message)
        receive_message(message)
      end

      def on_receive(message)
        unhandled(message)
      end

      def sender
        Actor::Ref.new(get_sender)
      end

      def schedule_message(message,update_interval,unit=:ms)
        if unit == :seconds
          unit = java.util.concurrent.TimeUnit::SECONDS
        elsif unit == :ms
          unit = java.util.concurrent.TimeUnit::MILLISECONDS
        else
          GameMachine.logger.error "Invalid unit argument for schedule_message (#{unit})"
          return
        end

        duration = GameMachine::JavaLib::Duration.create(update_interval, unit)
        scheduler = get_context.system.scheduler
        dispatcher = get_context.system.dispatcher
        scheduler.schedule(duration, duration, get_self, message, dispatcher, nil)
      end

      def schedule_message_once(message,update_interval,unit=:ms)
        if unit == :seconds
          unit = java.util.concurrent.TimeUnit::SECONDS
        elsif unit == :ms
          unit = java.util.concurrent.TimeUnit::MILLISECONDS
        else
          GameMachine.logger.error "Invalid unit argument for schedule_message (#{unit})"
          return
        end

        duration = GameMachine::JavaLib::Duration.create(update_interval, unit)
        scheduler = get_context.system.scheduler
        dispatcher = get_context.system.dispatcher
        scheduler.schedule_once(duration, get_self, message, dispatcher, nil)
      end

    end
  end
end
