package edu.mit.media.funf.probe2;

import java.lang.annotation.Documented;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.text.Annotation;
import android.util.Log;

import com.google.gson.JsonObject;
import static edu.mit.media.funf.Utils.TAG;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public interface Probe {
	
	public void enable();
	public void disable();
	
	public void setContext(Context context);
	public void setConfig(JsonObject config);
	public JsonObject getConfig();
	public JsonObject getDefaultConfig();
	public void setProbeFactory(ProbeFactory factory);

	public void addDataListener(DataListener listener);
	public void removeDataListener(DataListener listener);
	
	public void addStateListener(StateListener listener);
	public void removeStateListener(StateListener listener);
	
	public interface StartableProbe extends Probe {
		public static final double DEFAULT_PERIOD = 3600; // Once an hour
		public void start();
	}
	
	public interface ContinuousProbe extends StartableProbe {
		public static final double DEFAULT_DURATION = 60; // One minute
		public void stop();
	}
	
	public interface DiffableProbe extends Probe {
		// TODO: think of better names for these functions
		public boolean isDiffConfigured();
		public void setDiffParams(JsonObject diffParamValue);
		public JsonObject getUpdatedDiffParams(JsonObject oldDiffParamValue, JsonObject... newData);
	}
	
	@Documented
	@Retention(RUNTIME)
	@Target(TYPE)
	@Inherited
	public @interface DefaultSchedule {
		String value() default "";
	}
	
	@Documented
	@Retention(RUNTIME)
	@Target(TYPE)
	@Inherited
	public @interface DefaultConfig {
		String value() default "";
	}

	@Documented
	@Retention(RUNTIME)
	@Target(TYPE)
	@Inherited
	public @interface DisplayName {
		String value();
	}
	
	@Documented
	@Retention(RUNTIME)
	@Target(TYPE)
	@Inherited
	public @interface Description {
		String value();
	}
	
	@Documented
	@Retention(RUNTIME)
	@Target(TYPE)
	@Inherited
	public @interface RequiredPermissions {
		String[] value();
	}
	
	@Documented
	@Retention(RUNTIME)
	@Target(TYPE)
	@Inherited
	public @interface RequiredFeatures {
		String[] value();
	}
	
	/**
	 * Interface implemented by Probe data observers.
	 */
	public interface DataListener {

		/**
		 * Called when the probe emits data.  Data emitted from probes that extend the Probe class
		 * are guaranteed to have the PROBE and TIMESTAMP parameters.
		 * @param data
		 */
		public void onDataReceived(JsonObject data);
	}
	

	/**
	 * Interface implemented by Probe status observers.
	 */
	public interface StateListener {

		/**
		 * Called when the probe emits a status message, which can happen when the probe changes state.
		 * @param status
		 */
		public void onStateChanged(Probe probe);
	}
	

	/**
	 * Types to represent the current state of the probe.
	 * Provides the implementation of the ProbeRunnable state machine.
	 *
	 */
	public static enum State {

		// TODO: should we try catch, to prevent one probe from killing all probes?
		DISABLED {

			@Override
			protected void enable(Base probe) {
				synchronized (probe) {
					probe.state = ENABLED;

			        
					probe.onEnable();
				}
			}
	
			@Override
			protected void start(Base probe) {
				synchronized (probe) {
					enable(probe);
					if (probe.state == ENABLED) {
						ENABLED.start(probe);
					}
				}
			}
	
			@Override
			protected void stop(Base probe) {
				// Nothing
			}
	
			@Override
			protected void disable(Base probe) {
				// Nothing
			}
		},
		ENABLED {

			@Override
			protected void enable(Base probe) {
				// Nothing
			}
	
			@Override
			protected void start(Base probe) {
				if (probe instanceof Probe.StartableProbe) {
					synchronized (probe) {
						probe.state = RUNNING;
						probe.onStart();
					}
				} else {
					Log.w(TAG, "Attempted to start non-startable probe '" + probe.getClass().getName() + "'");
				}
			}
	
			@Override
			protected void stop(Base probe) {
				// Nothing
			}
	
			@Override
			protected void disable(Base probe) {
				synchronized (probe) {
					probe.state = DISABLED;
					probe.onDisable();
					// Shutdown handler thread
					probe.looper.quit();
					probe.looper = null;
					probe.handler = null;
				}
			}
		},
		RUNNING {

			@Override
			protected void enable(Base probe) {
				// Nothing
			}
	
			@Override
			protected void start(Base probe) {
				// Nothing
			}
	
			@Override
			protected void stop(Base probe) {
				synchronized (probe) {
					probe.state = ENABLED;
					probe.onStop();
				}
			}
	
			@Override
			protected void disable(Base probe) {
				synchronized (probe) {
					stop(probe);
					if (probe.state == ENABLED) {
						ENABLED.disable(probe);
					}
				}
			}
		};
		
		protected abstract void enable(Base probe);
		protected abstract void disable(Base probe);
		protected abstract void start(Base probe);
		protected abstract void stop(Base probe);
		
	}

	@DefaultSchedule
	@DefaultConfig
	public abstract class Base implements Probe {
		
		
		/**
		 * No argument constructor requires that setContext be called manually.
		 */
		public Base() {
			state = State.DISABLED;
		}
		
		public Base(Context context) {
			this();
			setContext(context);
		}
		
		public Base(Context context, ProbeFactory factory) {
			this(context);
			setProbeFactory(factory);
		}
		// TODO: figure out how to get scheduler to use source data requests to schedule appropriately
		// Probably will need to prototype with ActivityProbe
		public Map<String,JsonObject> getSourceDataRequests() {
			return null;
		}
		
		public JsonObject getDiffConfig(Bundle existingConfig, JsonObject... data) {
			return null;
		}
		
		private ProbeFactory probeFactory;
		public void setProbeFactory(ProbeFactory factory) {
			this.probeFactory = factory;
		}
		protected ProbeFactory getProbeFactory() {
			if (probeFactory == null) {
				synchronized (this) {
					if (probeFactory == null) {
						probeFactory = ProbeFactory.BasicProbeFactory.getInstance(getContext());
					}
				}
			}
			return probeFactory;
		}
	
		private Context context;
		public void setContext(Context context) {
			if (context == null) {
				throw new RuntimeException("Attempted to set a null context in probe '" + getClass().getName() + "'");
			}
			this.context = context.getApplicationContext();
		}
		protected Context getContext() {
			if (context == null) {
				throw new RuntimeException("Context was never set for probe '" + getClass().getName() + "'");
			}
			return context;
		}
		
		/*****************************************
		 * Probe Configuration
		 *****************************************/
		private JsonObject config;
		
		/**
		 * Changes the configuration for this probe.  Setting the configuration will disable the probe.
		 * @param config
		 */
		public synchronized void setConfig(JsonObject config) {
			disable();
			this.config = config;
		}
		public JsonObject getConfig() {
			if (config == null) {
				synchronized (this) {
					if (config == null) {
						this.config = new JsonObject();
					}
				}
			}
			return config;
		}
		
		/**
		 * Returns a copy of the default configuration that is used by the probe if no 
		 * configuration is specified.  This is also used to enumerate the 
		 * configuration options that are available.
		 * 
		 * The object returned by this function is a copy and can be modified.
		 * @return
		 */
		public JsonObject getDefaultConfig() {
			return new JsonObject();
		}
		
	
		/*****************************************
		 * Probe Data Listeners
		 *****************************************/
		
	
		
		private Set<DataListener> dataListeners = Collections.synchronizedSet(new HashSet<DataListener>());
		
		/**
		 * Returns the set of data listeners.  Make sure to synchronize on this object, 
		 * if you plan to modify it or iterate over it.
		 */
		protected Set<DataListener> getDataListeners() {
			return dataListeners;
		}
		
		@Override
		public void addDataListener(DataListener listener) {
			dataListeners.add(listener);
		}
	
		@Override
		public void removeDataListener(DataListener listener) {
			dataListeners.remove(listener);
		}
		
		protected void sendData(JsonObject data) {
			synchronized (dataListeners) {
				for (DataListener listener : dataListeners) {
					listener.onDataReceived(data);
				}
			}
		}
		
		
	
		
		
		
		/*****************************************
		 * Probe State Machine
		 *****************************************/
		
		private State state;
		
		/**
		 * Returns the current state of the probe.
		 */
		public State getState() {
			return state;
		}
		
		private synchronized void ensureLooperThreadExists() {
			if (looper == null) {
				HandlerThread thread = new HandlerThread("Probe[" + getClass().getName() + "]");
		        thread.start();
		        looper = thread.getLooper();
		        handler = new Handler(looper, new ProbeHandlerCallback());
			}
		}
		
		@Override
		public synchronized void enable() {
			ensureLooperThreadExists();
			handler.post(new Runnable() {
				@Override
				public void run() {
					state.enable(Base.this);
				}
			});
		}
	
		public synchronized void start() {
			ensureLooperThreadExists();
			handler.post(new Runnable() {
				@Override
				public void run() {
					state.start(Base.this);
				}
			});
		}
	
		public synchronized void stop() {
			ensureLooperThreadExists();
			handler.post(new Runnable() {
				@Override
				public void run() {
					state.stop(Base.this);
				}
			});
		}
	
		@Override
		public synchronized void disable() {
			if (handler != null) {
				handler.post(new Runnable() {
					@Override
					public void run() {
						state.disable(Base.this);
					}
				});
			}
		}
		
	
		/**
		 * Called when the probe switches from the disabled to the enabled state.  
		 * This is where any passive or opportunistic listeners should be configured.
		 */
		protected void onEnable() {
			
		}
		
		/**
		 * Called when the probe switches from the enabled state to active running state.  
		 * This should be used to send any data broadcasts, but must return quickly.
		 * If you have any long running processes they should be started on a separate thread 
		 * created by this method, or should be divided into short runnables that are posted to 
		 * this threads looper one at a time, to allow for the probe to change state.
		 */
		protected void onStart() {
			
		}
		
		/**
		 * Called with the probe switches from the running state to the enabled state.  
		 * This method should be used to stop any running threads emitting data, or remove
		 * a runnable that has been posted to this thread's looper.
		 * Any passive listeners should continue running.
		 */
		protected void onStop() {
			
		}
		
		/**
		 * Called with the probe switches from the enabled state to the disabled state.  
		 * This method should be used to stop any passive listeners created in the onEnable method.
		 * This is the time to cleanup and release any resources before the probe is destroyed.
		 */
		protected void onDisable() {
			
		}
		
	
		private volatile Looper looper;
		private volatile Handler handler;
		
		private class ProbeHandlerCallback implements Handler.Callback {
	
			@Override
			public boolean handleMessage(Message msg) {
				// TODO Auto-generated method stub
				return false;
			}
			
		}
		
		
	
		
		/*****************************************
		 * Probe State Listeners
		 *****************************************/
		
		
		private Set<StateListener> stateListeners = Collections.synchronizedSet(new HashSet<StateListener>());
		
		/**
		 * Returns the set of status listeners.  Make sure to synchronize on this object, 
		 * if you plan to modify it or iterate over it.
		 */
		protected Set<StateListener> getStateListeners() {
			return stateListeners;
		}
		
		@Override
		public void addStateListener(StateListener listener) {
			stateListeners.add(listener);
		}
	
		@Override
		public void removeStateListener(StateListener listener) {
			stateListeners.remove(listener);
		}
	}
}
