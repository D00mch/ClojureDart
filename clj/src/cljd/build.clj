;   Copyright (c) Baptiste Dupuch & Christophe Grand . All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljd.build
  (:require [cljd.compiler :as compiler]
            [clojure.tools.cli.api :as deps]
            [clojure.tools.cli :as ctc]
            [clojure.string :as str]
            [clojure.stacktrace :as st]
            [clojure.java.io :as io]
            [clojure.java.classpath :as cp]))

(def ^:dynamic *ansi* false)

(defn compile-core []
  (compiler/compile 'cljd.core))

(defn watch-dirs [dirs reload]
  (let [watcher (.newWatchService (java.nio.file.FileSystems/getDefault))
        reg1
        (fn [^java.io.File dir]
          (when (.isDirectory dir)
            [(.register (.toPath dir) watcher (into-array [java.nio.file.StandardWatchEventKinds/ENTRY_CREATE
                                                           java.nio.file.StandardWatchEventKinds/ENTRY_DELETE
                                                           java.nio.file.StandardWatchEventKinds/ENTRY_MODIFY
                                                           java.nio.file.StandardWatchEventKinds/OVERFLOW])
               (into-array [com.sun.nio.file.SensitivityWatchEventModifier/HIGH]))
             (.toPath dir)]))
        reg*
        (fn [dir]
          (eduction (keep reg1) (tree-seq some? #(.listFiles ^java.io.File %) dir)))]
    (loop [ks->dirs (into {} (mapcat reg*) dirs) to-reload #{}]
      (if-some [k (.poll watcher (if (seq to-reload) 10 1000) java.util.concurrent.TimeUnit/MILLISECONDS)]
        (let [events (.pollEvents k)] ; make sure we remove them, no matter what happens next
          (if-some [^java.nio.file.Path dir (ks->dirs k)]
            (let [[ks->dirs to-reload]
                  (reduce (fn [[ks->dirs to-reload] ^java.nio.file.WatchEvent e]
                            (let [f (some->> e .context (.resolve dir) .toFile)]
                              (if (and (some? f) (not (.isDirectory f))
                                    (re-matches #"[^.].*\.clj[dc]" (.getName f)))
                                [(into ks->dirs (some->> f reg*))
                                 (conj to-reload f)]
                                [ks->dirs to-reload])))
                    [ks->dirs to-reload] events)]
              (recur (cond-> ks->dirs (not (.reset k)) (dissoc ks->dirs k)) to-reload))
            (do
              (.cancel k)
              (recur ks->dirs to-reload))))
        (do
          (reload to-reload)
          (recur ks->dirs #{}))))))

(defn title [s]
  (if *ansi*
    (str "\u001B[1m" s "\u001B[0m")
    (str "=== " s " ===")))

(defn green [s]
  (if *ansi*
    (str "\u001B[1;32m" s "\u001B[0m")
    s))

(defn red [s]
  (if *ansi*
    (str "\u001B[1;31m" s "\u001B[0m")
    s))

(defn success []
  (rand-nth
    [(str (green "  All clear! ") "👌")
     (str (green "  You rock! ") "🤘")
     (str (green "  Bravissimo! ") "👏")
     (str (green "  Easy peasy! ") "😎")]))

(defn print-exception [e]
  (println (rand-nth
             [(str (red "Oh noes! ") "😵")
              (str (red "Something horrible happened! ") "😱")
              (str (red "$expletives ") "💩")]))
  (if-some [exprs (seq (::compiler/emit-stack (ex-data e)))]
    (let [exprs (into [] exprs)]
      (println (ex-message e))
      (run! prn (pop exprs))
      (println (title (pr-str (peek exprs))))
      (println (ex-message (ex-cause e))))
    (do
      (println (ex-message e))
      (st/print-stack-trace e))))

(defn timestamp []
  (.format (java.text.SimpleDateFormat. "@HH:mm" java.util.Locale/FRENCH) (java.util.Date.)))

(defn compile-cli
  [& {:keys [watch namespaces] :or {watch false}}]
  (binding [compiler/*lib-path* (str (System/getProperty "user.dir") "/lib/")
            compiler/*hosted* true
            compiler/dart-libs-info (compiler/load-libs-info)]
    (newline)
    (println (title "Compiling cljd.core"))
    (compile-core)
    (let [dirs (into #{} (map #(java.io.File. %)) (:paths (:basis (deps/basis nil))))
          dirty-nses (volatile! #{})
          compile-nses
          (fn [nses]
            (let [nses (into @dirty-nses nses)]
              (vreset! dirty-nses #{})
              (when (seq nses)
                (newline)
                (println (title "Compiling...") (timestamp))
                (run! #(println " " %) (sort nses))
                (try
                  (compiler/recompile nses)
                  (println (success))
                  (catch Exception e
                    (vreset! dirty-nses nses)
                    (print-exception e))))))
          compile-files
          (fn [files]
            (some->
              (for [^java.io.File f files
                    :let [fp (.toPath f)]
                    ^java.io.File d dirs
                    :let [dp (.toPath d)]
                    :when (.startsWith fp dp)
                    :let [ns (compiler/peek-ns f)]
                    :when ns]
                ns)
              seq
              compile-nses))]
      (compile-nses namespaces)
      (when watch
        (watch-dirs dirs compile-files)))))

(defn exec [& args]
  (let [opts (when (map? (first args)) (first args))
        [bin & args] (cond-> args opts next)
        pb (doto (ProcessBuilder. [])
             (-> .environment (.putAll (:env opts {})))
             (.redirectInput java.lang.ProcessBuilder$Redirect/INHERIT)
             (.redirectOutput (:out opts java.lang.ProcessBuilder$Redirect/INHERIT))
             (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT))
        path (-> pb .environment (get "PATH"))
        full-bin
        (or
          (some (fn [dirname]
                  (let [file (java.io.File. dirname bin)]
                    (when (and (.isFile file) (.canExecute file))
                      (.getAbsolutePath file))))
            (.split path java.io.File/pathSeparator))
          (throw (ex-info (str "Can't find " bin " on PATH.")
                   {:bin bin :path path})))
        process (.start (doto pb (.command (into [full-bin] args))))
        exit-code (.waitFor process)]
    (when-not (zero? exit-code) exit-code)))

(defn warm-up-libs-info! []
  (let [user-dir (System/getProperty "user.dir")
        cljd-dir (-> user-dir (java.io.File. ".clojuredart") (doto .mkdirs))
        lib-info-edn (java.io.File. cljd-dir "libs-info.edn")
        dart-tools-json (-> user-dir (java.io.File. ".dart_tool") (java.io.File. "package_config.json"))]
    (when-not (and (.exists lib-info-edn) (.exists dart-tools-json)
                (< (.lastModified dart-tools-json) (.lastModified lib-info-edn)))
      (let [analyzer-dart (java.io.File. cljd-dir "analyzer.dart")]
        (with-open [out (java.io.FileOutputStream. analyzer-dart)]
          (-> (Thread/currentThread) .getContextClassLoader (.getResourceAsStream "analyzer.dart") (.transferTo out)))
        (or
          (and
            (do
              (newline)
              (println (title "Adding dev dependencies"))
              (exec "flutter" "pub" "add" "-d" "analyzer:^3.3.1"))
            (do
              (newline)
              (println (title "Upgrading dev dependencies"))
              (exec "flutter" "pub" "upgrade" "analyzer:^3.3.1")))
          (do
            (newline)
            (println (title "Fetching dependencies"))
            (exec "flutter" "pub" "get"))
          (do
            (newline)
            (println (title "Dumping type information"))
            (exec {:out (java.lang.ProcessBuilder$Redirect/to lib-info-edn)}
              "flutter" "pub" "run" (.getPath analyzer-dart))))))))

(def cli-options
  [["-v" nil "Verbosity level; may be specified multiple times to increase value"
    :id :verbosity
    :default 0
    :update-fn inc]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["This program compiles Clojuredart files to dart files."
        "It compiles all required namespace."
        ""
        "Usage: program-name [options] action file1 file2 file3..."
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  compile Compile namespaces"
        "  watch   Compile namespaces and re-compile when a cljc or cljd file is modified."
        ""
        "Please refer to the manual page for more information."]
       (str/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary] :as kk} (ctc/parse-opts args cli-options)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}
      errors ; errors => exit with description of errors
      {:exit-message (error-msg errors)}
      ;; custom validation on arguments
      (and (< 1 (count arguments))
        (#{"compile" "watch"} (first arguments)))
      {:action (first arguments)
       :options options
       :namespaces (map symbol (next arguments))}
      :else ; failed custom validation => exit with usage summary
      {:exit-message (usage summary)})))

(defn -main [& args]
  (binding [*ansi* (and (System/console) (get (System/getenv) "TERM"))]
    (let [{:keys [action options exit-message namespaces ok?]} (validate-args args)]
      (if exit-message
        (exit (if ok? 0 1) exit-message)
        (do
          (println (title "Warming up `.clojuredart/libs-info.edn`") "(helps us emit better code)")
          (warm-up-libs-info!)
          (case action
            "watch" (compile-cli :namespaces namespaces :watch true)
            "compile" (compile-cli :namespaces namespaces)))))))
