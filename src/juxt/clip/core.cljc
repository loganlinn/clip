(ns juxt.clip.core
  (:refer-clojure :exclude [ref require])
  (:require [juxt.clip.impl.core :as impl]
            [clojure.walk :as walk]))

(defn- safely-derive-parts
  [components init & sccs-args]
  (let [g (impl/system-dependency-graph components)
        sccs (apply impl/sccs g init sccs-args)]
    (if-let [errors (seq (impl/dependency-errors sccs g))]
      (throw
        (ex-info
          (apply
            str
            "Could not construct execution order because:\n  "
            (interpose "\n  " (map impl/human-render-dependency-error errors)))
          {:components components
           :errors errors
           :g g
           :sccs sccs}))

      [g (map #(find components (first %)) sccs)])))

(defn require
  "Load all namespaces used by this system.  Suitable for use during AOT."
  [system-config]
  (doall
    (for [component (:components system-config)
          f [impl/pre-starting-f impl/starting-f impl/post-starting-f impl/stopping-f]]
      (f component))))

(defn start
  "Takes a system config to start.  Returns a running system where the keys map
  to the started component.  If provided, only the components in `component-ks`
  and their transitive dependencies will be started, otherwise all components.
  Runs the :pre-start, :start and :post-start keys in that order.
  
  :pre-start and :start may contains references, and will be executed without
  an implicit target.
  :post-start may also contain references, but will have the started component
  as an implict target.  This means that a symbol on it's own will work instead
  of requiring a code form, in addition the anaphoric variable `this` is
  available to refer to the started component."
  ([system-config component-ks]
   (let [{:keys [components executor]
          :or {executor impl/exec-queue}} system-config
         [_ component-chain] (safely-derive-parts components [] component-ks)]
     (executor
       (for [component component-chain
             f [impl/pre-starting-f
                impl/starting-f
                impl/post-starting-f]]
         (f component)))))
  ([system-config]
   (start system-config (keys (:components system-config)))))

(defn stop
  "Takes a system config to stop.

  Runs the :stop key, which may not contain references, and will be executed
  with the started component as an implict target.  If no :stop is provided and
  the target is AutoClosable then .close will be called on it, otherwise
  nothing."
  [system-config running-system]
  (let [{:keys [components executor]
         :or {executor impl/exec-queue}} system-config
        [_ component-chain] (safely-derive-parts
                              (select-keys components (keys running-system))
                              ())]
    (executor
      (map impl/stopping-f component-chain)
      running-system)))

(defn select
  "Return system-config with an updated :components to only contain components
  in `component-ks` & their transitive dependencies."
  [system-config component-ks]
  (update system-config
          :components
          (fn [components]
            (let [[_ component-chain] (safely-derive-parts components () component-ks)]
              (select-keys components (map key component-chain))))))

(comment
  (start
    {:components '{:foo {:start (clip/ref :bar)}
                   :bar {:start (clip/ref :foo)}
                   :baz {:start (clip/ref :baz)}
                   :foob {:start (clip/ref :nowhere)}}
     :executor impl/exec-queue})

  (let [system-config {:components '{:foo {:pre-start (prn "init:foo")
                                           :start 1
                                           :post-start prn
                                           :stop prn}
                                     :bar {:start (inc (clip/ref :foo))
                                           :stop prn}}
                       :executor impl/promesa-exec-queue}]
    (stop system-config (start system-config))))

(def exec-sync
  "Executor which runs sync between calls."
  impl/exec-queue)

(defn ref
  [ref-to]
  (list 'clip/ref ref-to))

(defn- deval-body
  "Takes a body of code and defers evaluation of lists."
  [body]
  (walk/postwalk
    (fn [x]
      (if (and (list? x)
               (not= (first x) 'clip/ref))
        (cons `list x)
        x))
    body))

(defmacro deval
  "EXPERIMENTAL.  Defers evaluation of lists.  Useful for defining component
  start/stop/etc. functions."
  [body]
  (deval-body body))

#?(:clj
   (defmacro with-deps
     "Takes bindings and a body, like `fn`. The first binding must be to deps, and
     be a destructuring of the deps you want.

     ```
     (with-deps [{:keys [foo bar]}]
     (+ foo bar))
     ```

     ```
     (with-deps [{foo :foo}]
     (str foo))
     ```

     Remaining bindings will be arguments to the generated function.  For example,
     :stop will provide you with the running instance to stop.

     ```
     (with-deps [{:keys [foo]} this]
     (.close this foo))
     ```"
     [[dep-binding & args] & body]
     (assert
       (map? dep-binding)
       "Please use a map to destructure the deps (known as associative destructuring)")
     (let [deps (mapv last
                      (filter
                        (fn [x]
                          (and (seq? x)
                               (= 'clojure.core/get (first x))))
                        (map second (partition 2 (destructure [dep-binding nil])))))]
       `(vary-meta
          ;; https://clojure.atlassian.net/browse/CLJ-2539
          ^::fix (fn self# ~(or (vec args) [])
                   (let [~dep-binding
                         (zipmap ~deps
                                 (map
                                   (fn [k#]
                                     (impl/get-ref impl/*running-system* k#))
                                   ~deps))]
                     ~@body))
          assoc
          ::deps
          ~deps))))

#?(:clj (defmacro with-system
          "Takes a binding and a system like with-open, and tries to close the
          system even when stopping causes an exception.
          
          If an exception is thrown during start, then the partially started
          system will be stopped and the exception rethrown.  If stopping that
          system throws an exception, a new exception will be thrown with the
          original exception as the cause, and the stopping exception under
          `::stop-exception`.

          If an exception is thrown during the final stop, then the exception
          will be thrown."
          [[binding system-config] & body]
          `(let [system-config# ~system-config
                 system#
                 (try
                   (start system-config#)
                   (catch Exception e#
                     (when-let [partial-system# (::system (ex-data e#))]
                       (try
                         (stop system-config# partial-system#)
                         (catch Exception e-stop#
                           (throw
                             (ex-info
                               "Exception thrown while starting system, failed to stop partially started system."
                               {::stop-exception e-stop#}
                               e#))
                           )))
                     (throw e#)))
                 ~binding system#]
             (try
               ~@body
               (finally
                 (stop system-config# system#))))))
