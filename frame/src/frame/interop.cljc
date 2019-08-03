(ns frame.interop
  #?(:cljs (:require [goog.async.nextTick]))
  #?(:clj (:import [java.util.concurrent Executor Executors])
     :cljr (:import [System.Threading Thread ThreadStart])))

#?(:clj
   (defonce ^:private executor (Executors/newSingleThreadExecutor))
   :cljr
   (do ; XXX: apparently no c# equivalent of SingleThreadExecutor
     (defonce ^:private task-queue
       (atom clojure.lang.PersistentQueue/EMPTY))
     (defn ^:private do-task
       []
       (when-let [task-fn (ffirst (swap-vals! task-queue pop))]
         (task-fn))
       (Thread/Sleep 1))
     (defn task-queue-processor
       []
       (Thread.
        (gen-delegate ThreadStart []
          (while true
            (do-task)))))
     (defn execute [task-fn]
       (swap! task-queue conj task-fn))
     (.Start (task-queue-processor))))

(def next-tick
  #?(:clj (fn [f]
            (let [bound-f (bound-fn [& args] (apply f args))]
              (.execute ^Executor executor bound-f))
            nil)
     :cljr (fn [f]
             (let [bound-f (bound-fn [& args] (apply f args))]
               (execute bound-f)))
     :cljs goog.async.nextTick))

(def empty-queue
  #?(:cljs #queue []
     :default clojure.lang.PersistentQueue/EMPTY))

(def after-render next-tick)

#?(:cljs
   ;; Make sure the Google Closure compiler sees this as a boolean constant,
   ;; otherwise Dead Code Elimination won't happen in `:advanced` builds.
   ;; Type hints have been liberally sprinkled.
   ;; https://developers.google.com/closure/compiler/docs/js-for-compiler
   (def ^boolean debug-enabled? "@define {boolean}"
     false ;; this this off for now since it is so verbose when punk isn't connected
     #_goog/DEBUG)
   :default
   (def debug-enabled? true))

(defn deref? [x]
  #?(:cljs
     (satisfies? IDeref x)
     :default
     (instance? clojure.lang.IDeref x)))

(defn set-timeout! [f ms]
  #?(:cljs
     (js/setTimeout f ms)
     :default
     ;; Note that we ignore the `ms` value and just invoke the
     ;; function, because there isn't often much point firing a timed
     ;; event in a test."
     (next-tick f)))

(defn now []
  #?(:clj
     ;; currentTimeMillis may count backwards in some scenarios, but
     ;; as this is used for tracing it is preferable to the slower but
     ;; more accurate System.nanoTime.
     (System/currentTimeMillis)
     :cljr
     (.ToUnixTimeMilliseconds (DateTimeOffset/UtcNow))
     :cljs
     (if (and
          (exists? js/performance)
          (exists? js/performance.now))
       (js/performance.now)
       (js/Date.now))))

;; (defn reagent-id
;;   "Produces an id for reactive Reagent values
;;   e.g. reactions, ratoms, cursors."
;;   [reactive-val]
;;   (when (implements? reagent.ratom/IReactiveAtom reactive-val)
;;     (str (condp instance? reactive-val
;;            reagent.ratom/RAtom "ra"
;;            reagent.ratom/RCursor "rc"
;;            reagent.ratom/Reaction "rx"
;;            reagent.ratom/Track "tr"
;;            "other")
;;          (hash reactive-val))))
