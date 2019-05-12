(ns chlorine.repl
  (:require [clojure.string :as str]
            [repl-tooling.eval :as eval]
            [repl-tooling.repl-client.clojure :as clj-repl]
            [chlorine.state :refer [state]]
            [repl-tooling.repl-client.clojurescript :as cljs]
            [repl-tooling.editor-helpers :as helpers]
            [chlorine.ui.inline-results :as inline]
            [reagent.core :as r]
            [chlorine.ui.console :as console]
            [repl-tooling.repl-client :as repl-client]
            [repl-tooling.integrations.connection :as conn]
            [repl-tooling.editor-integration.connection :as connection]
            [chlorine.ui.atom :as atom]
            [clojure.core.async :as async :include-macros true]
            [repl-tooling.editor-integration.renderer :as render]
            [clojure.walk :as walk]))

(defn- handle-disconnect! []
  (swap! state assoc
         :repls {:clj-eval nil
                 :cljs-eval nil
                 :clj-aux nil}
         :connection nil
         :results {})
  (atom/info "Disconnected from REPLs" ""))

(defn- patch-element [element id new-val]
  (let [dref (cond-> element (instance? reagent.ratom/RAtom element) deref)]
    (prn :INST
         (instance? render/Patch dref)
         id
         (:id dref))
    (if (and (instance? render/Patch dref) (= id (:id dref)))
      (prn :OKAY? (swap! element assoc :obj-ratom
                         (render/parse-result {:result new-val :parsed? true}
                                              (:repl dref))))
      dref)))

(defn- treat-stdout [out]
  (if (re-find #"#repl-tooling/patch \[.*" (str out))
    (let [[id new-val] (-> out helpers/read-result helpers/obj)
          values (-> @state :results vals)]
      (walk/prewalk #(patch-element % id new-val) values))
    (some-> ^js @console/console (.stdout out))))

(defn connect! [host port]
  (let [p (connection/connect-unrepl!
           host port
           {:on-stdout
            treat-stdout
            :on-stderr
            #(some-> ^js @console/console (.stderr %))
            :on-result
            #(when (:result %) (inline/render-on-console! @console/console %))
            :on-disconnect
            #(handle-disconnect!)})]
    (.then p (fn [repls]
               (atom/info "Clojure REPL connected" "")
               (console/open-console (-> @state :config :console-pos)
                                     #(connection/disconnect!))
               (swap! state #(-> %
                                 (assoc-in [:repls :clj-eval] (:clj/repl repls))
                                 (assoc-in [:repls :clj-aux] (:clj/aux repls))
                                 (assoc :connection {:host host :port port})))))))

(defn callback [output]
  (when (nil? output)
    (handle-disconnect!))

  (when-let [out (:out output)]
    (some-> ^js @console/console (.stdout out)))
  (when-let [out (:err output)]
    (some-> ^js @console/console (.stderr out)))
  (when (contains? output :result)
    (inline/render-on-console! @console/console output)))

(defn connect-cljs! [host port]
  (let [repl (cljs/repl :clj-eval host port callback)]
    (eval/evaluate repl ":ok" {} (fn []
                                   (atom/info "ClojureScript REPL connected" "")
                                   (console/open-console (-> @state :config :console-pos)
                                                         #(connection/disconnect!))
                                   (swap! state
                                          #(-> %
                                               (assoc-in [:repls :cljs-eval] repl)
                                               (assoc :connection {:host host
                                                                   :port port})))))))

(def trs {:no-shadow-file "File shadow-cljs.edn not found"
          :no-worker "No worker for first build ID"
          :unknown "Unknown error"})

(defn connect-self-hosted []
  (let [{:keys [host port]} (:connection @state)
        dirs (->> js/atom .-project .getDirectories (map #(.getPath ^js %)))]
    (.. (conn/auto-connect-embedded! host port dirs
                                     {:on-stdout
                                      treat-stdout
                                      :on-result
                                      #(when (:result %)
                                         (inline/render-on-console! @console/console %))})

        (then #(if-let [error (:error %)]
                 (atom/error "Error connecting to ClojureScript"
                             (get trs error error))
                 (do
                   (swap! state assoc-in [:repls :cljs-eval] %)
                   (atom/info "ClojureScript REPL connected" "")))))))

(defn- prepare-result [inline-result parsed]
  (swap! state assoc-in [:results inline-result] parsed)
  (js/setTimeout #(swap! state update :results dissoc inline-result)
                 10000))
(defn set-inline-result [inline-result eval-result]
  (let [parsed (render/parse-result eval-result (-> @state :repls :clj-eval))]
    (prepare-result inline-result parsed)
    (if (contains? eval-result :result)
      (inline/render-inline! inline-result parsed)
      (do
        (some-> @state :repls :clj-eval
                (eval/evaluate "(clojure.repl/pst)" {} identity))
        (inline/render-error! inline-result eval-result)))))

(defn need-cljs? [editor]
  (or
   (-> @state :config :eval-mode (= :cljs))
   (and (-> @state :config :eval-mode (= :discover))
        (str/ends-with? (str (.getFileName editor)) ".cljs"))))

(defn- eval-cljs [editor ns-name filename row col code ^js result opts callback]
  (if-let [repl (-> @state :repls :cljs-eval)]
    (eval/evaluate repl code
                   {:namespace ns-name :row row :col col :filename filename
                    :pass opts}
                   callback)
    (do
      (some-> result .destroy)
      (atom/error "REPL not connected"
                  (str "REPL not connected for ClojureScript.\n\n"
                       "You can connect a repl using "
                       "'Connect ClojureScript Socket REPL' command,"
                       "or 'Connect a self-hosted ClojureScript' command")))))

(defn evaluate-aux
  ([^js editor ns-name filename row col code callback]
   (evaluate-aux editor ns-name filename row col code {} callback))
  ([^js editor ns-name filename row col code opts callback]
   (if (need-cljs? editor)
     (eval-cljs editor ns-name filename row col code nil opts #(-> % helpers/parse-result callback))
     (some-> @state :repls :clj-aux
             (eval/evaluate code
                            {:namespace ns-name :row row :col col :filename filename
                             :pass opts}
                            #(-> % helpers/parse-result callback))))))

(defn eval-and-present
  ([^js editor ns-name filename ^js range code]
   (eval-and-present editor ns-name filename range code {}))
  ([^js editor ns-name filename ^js range code opts]
   (let [result (inline/new-result editor (.. range -end -row))
         row (.. range -start -row)
         col (.. range -start -column)]

     (if (need-cljs? editor)
       (eval-cljs editor ns-name filename row col code result opts
                  #(set-inline-result result %))
       (some-> @state :repls :clj-eval
               (eval/evaluate code
                              {:namespace ns-name :row row :col col :filename filename
                               :pass opts}
                              #(set-inline-result result %)))))))

(def ^:private EditorUtils (js/require "./editor-utils"))
(defn top-level-code [^js editor ^js range]
  (let [range (. EditorUtils
                (getCursorInBlockRange editor #js {:topLevel true}))]
    [range (some->> range (.getTextInBufferRange editor))]))

(defn ns-for [^js editor]
  (.. EditorUtils (findNsDeclaration editor)))

(defn evaluate-top-block!
  ([] (evaluate-top-block! (atom/current-editor)))
  ([^js editor]
   (let [range (. EditorUtils
                 (getCursorInBlockRange editor #js {:topLevel true}))]
     (some->> range
              (.getTextInBufferRange editor)
              (eval-and-present editor
                                (ns-for editor)
                                (.getPath editor)
                                range)))))

(defn evaluate-block!
  ([] (evaluate-block! (atom/current-editor)))
  ([^js editor]
   (let [range (. EditorUtils
                 (getCursorInBlockRange editor))]
     (some->> range
              (.getTextInBufferRange editor)
              (eval-and-present editor
                                (ns-for editor)
                                (.getPath editor)
                                range)))))

(defn evaluate-selection!
  ([] (evaluate-selection! (atom/current-editor)))
  ([^js editor]
   (eval-and-present editor
                     (ns-for editor)
                     (.getPath editor)
                     (. editor getSelectedBufferRange)
                     (.getSelectedText editor))))

(defn wrap-in-rebl-submit
  "Clojure 1.10 only, require REBL on the classpath (and UI open)."
  [code]
  (str "(let [value " code "]"
       " (try"
       "  ((requiring-resolve 'cognitect.rebl/submit) '" code " value)"
       "  (catch Throwable _))"
       " value)"))

(defn inspect-top-block!
  ([] (inspect-top-block! (atom/current-editor)))
  ([^js editor]
   (let [range (. EditorUtils
                 (getCursorInBlockRange editor #js {:topLevel true}))]
     (some->> range
              (.getTextInBufferRange editor)
              (wrap-in-rebl-submit)
              (eval-and-present editor
                                (ns-for editor)
                                (.getPath editor)
                                range)))))

(defn inspect-block!
  ([] (inspect-block! (atom/current-editor)))
  ([^js editor]
   (let [range (. EditorUtils
                 (getCursorInBlockRange editor))]
     (some->> range
              (.getTextInBufferRange editor)
              (wrap-in-rebl-submit)
              (eval-and-present editor
                                (ns-for editor)
                                (.getPath editor)
                                range)))))

(defn run-tests-in-ns!
  ([] (run-tests-in-ns! (atom/current-editor)))
  ([^js editor]
   (let [pos (.getCursorBufferPosition editor)]
     (evaluate-aux editor
                   (ns-for editor)
                   (.getFileName editor)
                   (.. pos -row)
                   (.. pos -column)
                   "(clojure.test/run-tests)"
                   #(let [{:keys [test pass fail error]} (:result %)]
                      (atom/info "(clojure.test/run-tests)"
                                 (str "Ran " test " test"
                                      (when-not (= 1 test) "s")
                                      (when-not (zero? pass)
                                        (str ", " pass " assertion"
                                             (when-not (= 1 pass) "s")
                                             " passed"))
                                      (when-not (zero? fail)
                                        (str ", " fail " failed"))
                                      (when-not (zero? error)
                                        (str ", " error " errored"))
                                      ".")))))))

(defn run-test-at-cursor!
  ([] (run-test-at-cursor! (atom/current-editor)))
  ([^js editor]
   (let [pos  (.getCursorBufferPosition editor)
         s    (atom/current-var editor)
         code (str "(do"
                   " (clojure.test/test-vars [#'" s "])"
                   " (println \"Tested\" '" s "))")]
     (evaluate-aux editor
                   (ns-for editor)
                   (.getFileName editor)
                   (.. pos -row)
                   (.. pos -column)
                   code
                   #(atom/info (str "Tested " s)
                               "See REPL for any failures.")))))

(defn load-file!
  ([] (load-file! (atom/current-editor)))
  ([^js editor]
   (let [file-name (.getPath editor)
         ;; canonicalize path separator for Java -- this avoids problems
         ;; with \ causing 'bad escape characters' in the strings below
         file-name (str/replace file-name "\\" "/")
         code (str "(do"
                   " (require 'clojure.string)"
                   " (println \"Loading\" \"" file-name "\")"
                   " (try "
                   "  (let [path \"" file-name "\""
                   ;; if target REPL is running on *nix-like O/S...
                   "        nix? (clojure.string/starts-with? (System/getProperty \"user.dir\") \"/\")"
                   ;; ...and the file path looks like Windows...
                   "        win? (clojure.string/starts-with? (subs path 1) \":/\")"
                   ;; ...extract the driver letter...
                   "        drv  (clojure.string/lower-case (subs path 0 1))"
                   ;; ...and map to a Windows Subsystem for Linux mount path:
                   "        path (if (and nix? win?) (str \"/mnt/\" drv (subs path 2)) path)]"
                   "   (load-file path))"
                   "  (catch Throwable t"
                   "   (doseq [e (:via (Throwable->map t))]"
                   "    (println (:message e))))))")]
     (evaluate-aux editor
                   (ns-for editor)
                   (.getFileName editor)
                   1
                   0
                   code
                   #(atom/info "Loaded file" file-name)))))

(defn source-for-var!
  ([] (source-for-var! (atom/current-editor)))
  ([^js editor]
   (let [pos  (.getCursorBufferPosition editor)
         s    (atom/current-var editor)
         code (str "(do"
                   " (require 'clojure.repl)"
                   " (clojure.repl/source " s "))")]
     (if (need-cljs? editor)
       (atom/warn "Source For Var is only supported for Clojure" "")
       (evaluate-aux editor
                     (ns-for editor)
                     (.getFileName editor)
                     (.. pos -row)
                     (.. pos -column)
                     code
                     identity)))))

(def exports
  #js {:eval_and_present eval-and-present
       :eval_and_present_at_pos (fn [code]
                                  (let [editor ^js (atom/current-editor)]
                                    (eval-and-present editor
                                                      (ns-for editor)
                                                      (.getPath editor)
                                                      (. editor getSelectedBufferRange)
                                                      code)))})
