(ns chlorine.repl
  (:require [chlorine.state :refer [state]]
            [repl-tooling.editor-helpers :as helpers]
            [chlorine.ui.inline-results :as inline]
            [chlorine.ui.console :as c-console]
            [chlorine.ui.atom :as atom]
            [promesa.core :as p]
            [repl-tooling.editor-integration.renderer.console :as console]
            [repl-tooling.editor-integration.connection :as connection]
            [repl-tooling.editor-integration.schemas :as schemas]
            [schema.core :as s]
            ["atom" :refer [CompositeDisposable]]
            ["path" :refer [join]]))

(defonce ^:private commands-subs (atom (CompositeDisposable.)))

(defn- handle-disconnect! []
  (let [repls (:repls @state)
        any-repl (or (:clj-eval repls) (:cljs-eval repls))]
    (when any-repl
      (atom/info "Disconnected from REPLs" "")))

  (swap! state assoc
         :tooling-state nil
         :repls {:clj-eval nil
                 :cljs-eval nil
                 :clj-aux nil}
         :connection nil)
  (.dispose ^js @commands-subs))

(defn- decide-command [command]
  (let [old-cmd (:old-command command)
        new-cmd (:command command)]
    (fn []
      (if (and old-cmd (-> @state :config :experimental-features (not= true)))
        (old-cmd)
        (new-cmd)))))

(defn- register-commands! [commands]
  (.dispose ^js @commands-subs)
  (reset! commands-subs (CompositeDisposable.))
  (doseq [[k command] commands
          :let [disp (-> js/atom
                         .-commands
                         (.add "atom-text-editor"
                               (str "chlorine:" (name k))
                               (decide-command command)))]]
    (.add ^js @commands-subs disp)))

(s/defn get-editor-data :- schemas/EditorData []
  (when-let [editor (atom/current-editor)]
    (let [range (.getSelectedBufferRange editor)
          start (.-start range)
          end (.-end range)]
      {:editor editor
       :contents (.getText editor)
       :filename (.getPath editor)
       :range [[(.-row start) (.-column start)]
               [(.-row end) (cond-> (.-column end)
                                    (not= (.-column start) (.-column end)) dec)]]})))

(defn- notify! [{:keys [type title message]}]
  (case type
    :info (atom/info title message)
    :warn (atom/warn title message)
    (atom/error title message)))

(defn- prompt! [{:keys [title message arguments]}]
  (js/Promise.
   (fn [resolve]
     (let [notification (atom nil)
           buttons (->> arguments (map (fn [{:keys [key value]}]
                                         {:text value
                                          :onDidClick (fn []
                                                        (resolve key)
                                                        (.dismiss ^js @notification))})))]

       (reset! notification (.. js/atom -notifications
                                (addInfo title (clj->js {:detail message
                                                         :dismissable true
                                                         :buttons buttons}))))
       (.onDidDismiss ^js @notification #(fn [] (resolve nil) true))))))

(defn- on-copy! [txt]
  (.. js/atom -clipboard (write txt))
  (atom/info "Copied result" ""))

(s/defn get-config :- schemas/Config []
  (assoc (:config @state)
         :project-paths (->> js/atom
                             .-project
                             .getDirectories
                             (map #(.getPath ^js %)))))

(defn- open-ro-editor [file-name line col position contents]
  (.. js/atom
      -workspace
      (open file-name position)
      (then #(doto ^js %
                   (aset "isModified" (constantly false))
                   (aset "save" (fn [ & _] (atom/warn "Can't save readonly editor" "")))
                   (.setText contents)
                   (.setReadOnly true)
                   (.setCursorBufferPosition #js [line (or col 0)])))))

(defn- open-editor [{:keys [file-name line contents column]}]
  (let [position (clj->js (cond-> {:initialLine line :searchAllPanes true}
                                  column (assoc :initialColumn column)))]
    (if contents
      (open-ro-editor file-name line column position contents)
      (.. js/atom -workspace (open file-name position)))))

(defn connect-socket! [host port]
  (let [p (connection/connect!
           host port
           {:on-stdout console/stdout
            :config-file-path (join (.getConfigDirPath js/atom) "chlorine-config.cljs")
            :register-commands register-commands!
            :on-stderr console/stderr
            :on-disconnect handle-disconnect!
            :on-start-eval #(inline/new-result %)
            :on-eval (fn [res]
                       (c-console/result res)
                       (inline/update-result res))
            :get-rendered-results #(concat (inline/all-parsed-results)
                                           (->> @console/out-state
                                                (filter (fn [r] (-> r first (= :result))))
                                                (map second)))
            :on-copy on-copy!
            :editor-data get-editor-data
            :open-editor open-editor
            :get-config get-config
            :notify notify!
            :prompt prompt!})]

    (.then p (fn [st]
               (when st
                 (c-console/open-console (-> @state :config :console-pos)
                                         #(connection/disconnect!))
                 (swap! state #(-> %
                                   (assoc-in [:repls :clj-eval] (:clj/repl @st))
                                   (assoc-in [:repls :clj-aux] (:clj/aux @st))
                                   (assoc :parse (-> @st :editor/features :result-for-renderer))
                                   (assoc :connection {:host host :port port}
                                          ; FIXME: This is just here so we can migrate
                                          ; code to REPL-Tooling little by little
                                          :tooling-state st))))))))

(defn- txt-in-range []
  (let [{:keys [contents range]} (get-editor-data)]
    [range (helpers/text-in-range contents range)]))

(defn get-code [kind]
  (when-let [editor (atom/current-editor)]
    (let [range (.getSelectedBufferRange editor)
          start (.-start range)
          row (.-row start)
          col (.-column start)
          contents (.getText editor)
          [range text] (case kind
                         "top-block" (helpers/top-block-for contents [row col])
                         "block" (helpers/block-for contents [row col])
                         "var" (helpers/current-var contents [row col])
                         "selection" (txt-in-range)
                         "ns" (helpers/ns-range-for contents [row col]))]
      (clj->js {:text text
                :range range}))))

(defn evaluate-and-present [code range]
  (when-let [command (some-> @state :tooling-state deref
                             :editor/features :evaluate-and-render)]
    (command (js->clj {:text code :range range}))))

(defn evaluate-interactive [code range]
  (when-let [command (some-> @state :tooling-state deref
                             :editor/features :evaluate-and-render)]
    (command (js->clj {:text code
                       :range range
                       :pass {:aux true :interactive true}}))))

(defn wrap-in-rebl-submit
  "Clojure 1.10 only, require REBL on the classpath (and UI open)."
  [code]
  (str "(let [value " code "]"
       " (try"
       "  ((requiring-resolve 'cognitect.rebl/submit) '" code " value)"
       "  (catch Throwable _))"
       " value)"))

(defn inspect-top-block! []
  (let [res (get-code "top-block")]
    (some-> (.-text res)
            (wrap-in-rebl-submit)
            (evaluate-and-present (.-range res)))))

(defn inspect-block! []
  (let [res (get-code "block")]
    (some-> (.-text res)
            (wrap-in-rebl-submit)
            (evaluate-and-present (.-range res)))))

(defn clear-inline! []
  (inline/clear-results! (atom/current-editor)))

(def exports
<<<<<<< Updated upstream
  #js {:get_top_block #(get-code "top-block")
       :get_block #(get-code "block")
       :get_var #(get-code "var")
       :get_selection #(get-code "selection")
       :get_namespace #(get-code "ns")
       :evaluate_and_present evaluate-and-present
       :evaluate_interactive evaluate-interactive})
=======
  #js {:eval_and_present eval-and-present
       :console (fn [] @console/console)
       :connect_bang connect!
       :eval_and_present_at_pos (fn [code]
                                  (let [editor (atom/current-editor)]
                                    (eval-and-present editor
                                                      (ns-for editor)
                                                      (.getPath editor)
                                                      (. editor getSelectedBufferRange)
                                                      code)))})
>>>>>>> Stashed changes
