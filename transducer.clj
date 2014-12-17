(ns transducer.xform
  (:require [clojure.core.async :as async :refer [<!! !!>]]))


(defn to-proc< [in]
  (let [out (async/chan 1)]
    (async/pipe in out)
    out))

(defn pipeline< [desc c]
  (let [p (partition 2 desc)]
    (reduce (fn [prev-c [n f]]
      (-> (dotime [_ n]
            (async/map< f prev-c)
            to-proc<)
	  async/merge))
      c
      p)))



; transducer is a function that takes a normal fn that can be apply to cursor args during reduce step,
; return a fn that transform/wraps A reducer step fn, and lead to the final 3-arity reduce step fn. 
; transducer fn must be 3-arity fn, 
;  0 - flow thru, 
;  1 - do nothing on result on this step, 
;  2 - reduce step result to final result.
;
(defn map                 ; without coll, (map f) return a transducer
  ([f]	                  ; take f that apply to reduce cursor arg
    (fn [reducer-step-f]  ; transform/wrap a reducer step fn
      (fn                 ;  ret a reducer 3-arity step fn
        ([] (reducer-step-f))
        ([result] (reducer-step-f result))
        ([result input]
          (reducer-step-f result (f input)))))))


;
; filter pred without coll will ret transducer.
(defn filter 
  ([pred]
    (fn [step-f]
      (fn 
        [] (step-f)
        [result] (step-f result)
        [result input] 
          (if (pred input)
            (step-f result input) 
            result))))
   ([pred coll]
     (sequence (filter pred) coll)))


;
; without coll as last arg, ret a transducer.
(defn take-with
  [pred]
  (fn [step-f]
    (fn [tot cursor]
      (if (pred cursor)
        (step-f tot cursor)
        (reduced tot)))))
 
;
; transducer with state, need to create state on every step
(defn dropping-while
  [pred]
  (fn [stepf]
    (let [dv (volatile! true)]
      (fn [r x]
        (let [drop? @dv]
          (if (and drop? (pred x))
            r
            (do
              (vreset! dv false)
              (stepf r x))))))))


; transduce can be viewed as threading a seq, but independent of source of input(aseq) and job(lazy seq creation)
(->> aseq (map inc) (filter even?))

(def xform (comp (map inc) (filter even?)))

; transducer take transducre fn, normal reducer step fn,
; use transducer fn as step fn when calling normal reduce, and apply transducer completing on the return result. 
(defn transducer
  ([xform step-f coll] (transducer xform step-f (step-f) coll))
  ([xform step-f init coll]
    (let [xf (xform step-f)
         ret (reduce xf init coll)]
      ;; finally, apply completing helper
      (xf ret))))

;
; application of transducer
;

; lazily transform the data (one lazy sequence, not three as with composed sequence functions)
(sequence xform data)

; reduce with a transformation (no laziness, just a loop)
(transduce xform + 0 data)

; build one collection from a transformation of another, again no laziness
(into [] xform data)

; create a recipe for a transformation, which can be subsequently sequenced, iterated or reduced
(iteration xform data)

; or use the same transducer to transform everything that goes through a channel
; this demonstrates the corresponding new capability of core.async channels - they can take transducers.
(chan 1 xform)



