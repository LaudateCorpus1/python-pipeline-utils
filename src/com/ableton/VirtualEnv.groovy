package com.ableton

import com.cloudbees.groovy.cps.NonCPS


/**
 * Provides a minimal wrapper around a Python virtualenv environment. The virtualenv is
 * stored under the workspace.
 */
class VirtualEnv implements Serializable {
  /**
   * Script context.
   * <strong>Required value, may not be null!</strong>
   */
  Object script
  /**
   * Destination directory for the virtualenv. This value is set during construction of
   * the object, and is under the workspace.
   */
  String destDir = null
  /**
   * Series of commands needed to activate a virtualenv inside the current shell.
   */
  String activateCommands = null

  protected String activateSubDir = null

  /**
   * Create a virtualenv using a specific version of Python, installed via pyenv. pyenv
   * should be installed on the node, but the actual setup of any required environment
   * variables (e.g. PYENV_ROOT and PATH) will be done inside this function.
   *
   * @param script Script context.
   *               <strong>Required value, may not be null!</strong>
   * @param python Python version, as given by pyenv versions --list.
   * @param pyenvRoot Path to the installation of pyenv.
   * @return New instance of VirtualEnv object.
   */
  static VirtualEnv create(Object script, String python, String pyenvRoot) {
    if (!script.isUnix()) {
      script.error 'This method is not supported on Windows'
    }

    VirtualEnv venv = new VirtualEnv(script)
    assert pyenvRoot

    if (!script.fileExists(pyenvRoot)) {
      script.error "pyenv root path '${pyenvRoot}' does not exist"
    }

    venv.activateCommands = """
      export PYENV_ROOT=${pyenvRoot}
      export PATH=\$PYENV_ROOT/bin:\$PATH
      eval "\$(pyenv init --path)"
      eval "\$(pyenv init -)"
    """

    venv.script.sh(
      label: "Install Python version ${python} with pyenv",
      script: """
        ${venv.activateCommands}
        pyenv install --skip-existing ${python}
        pyenv shell ${python}
        pip install virtualenv
        virtualenv ${venv.destDir}
      """,
    )

    venv.activateCommands += ". ${venv.destDir}/${venv.activateSubDir}/activate"

    return venv
  }

  /**
   * Create a virtualenv using a specific locally installed version of Python.
   *
   * @param script Script context.
   *               <strong>Required value, may not be null!</strong>
   * @param python Python version or absolute path to Python executable.
   * @param randomSeed If non-zero, use this seed for the random number generator.
   * @return New instance of VirtualEnv object.
   */
  static VirtualEnv create(Object script, String python, long randomSeed = 0) {
    VirtualEnv venv = new VirtualEnv(script, randomSeed)
    venv.script.sh(
      label: "Create virtualenv for ${python}",
      script: "virtualenv --python=${python} ${venv.destDir}",
    )
    return venv
  }

  /**
   * Construct a new instance of this class. This method <strong>does not</strong>
   * initialize the environment by running {@code virtualenv}. Use the factory method
   * {@link #create(Object, String)} instead.
   *
   * @param script Script context. <strong>Required value, may not be null!</strong>
   * @param randomSeed If non-zero, use this seed for the random number generator.
   * @see #create(Object, String)
   */
  VirtualEnv(Object script, long randomSeed = 0) {
    assert script

    this.script = script
    String workspace = script.env.WORKSPACE

    if (script.isUnix()) {
      activateSubDir = 'bin'
    } else {
      activateSubDir = 'Scripts'
      workspace = workspace.replace('\\', '/')
    }

    long seed = randomSeed ?: System.currentTimeMillis() * this.hashCode()
    this.destDir = "${workspace}/.venv/venv-${randomName(seed)}"
    this.activateCommands = ". ${destDir}/${activateSubDir}/activate"
  }

  /**
   * Removes the virtualenv from disk. You can call this method in the cleanup stage of
   * the pipeline to avoid cluttering the build node with temporary files. Note that the
   * virtualenv is stored underneath the workspace, so removing the workspace will have
   * the same effect.
   */
  void cleanup() {
    script.dir(destDir) {
      script.deleteDir()
    }
  }

  /**
   * Run a command in the virtualenv.
   *
   * @param arguments Arguments to pass to the {@code sh} command. See the documentation
   *        for the {@code sh} step for valid arguments.
   */
  @SuppressWarnings('MethodReturnTypeRequired')
  def run(Map arguments) {
    assert arguments
    assert arguments.containsKey('script')

    String scriptCommand = """
      ${this.activateCommands}
      ${arguments.script}
    """
    // We shouldn't modify the original arguments map
    Map newArguments = arguments.clone()
    // Replace the original `script` command with the venv-activated one
    newArguments['script'] = scriptCommand
    newArguments['label'] = arguments.label ?:
      "Run command in virtualenv: ${arguments.script}"
    return script.sh(newArguments)
  }

  /**
   * Run a command in the virtualenv.
   *
   * @param command Command to run.
   */
  void run(String command) {
    script.sh(
      label: "Run command in virtualenv: ${command}",
      script: """
        ${this.activateCommands}
        ${command}
      """,
    )
  }

  @NonCPS
  protected static String randomName(long seed) {
    String pool = '0123456789'
    int length = 8
    @SuppressWarnings('InsecureRandom')
    Random random = new Random(seed)
    List randomChars = (0..length - 1).collect {
      pool[random.nextInt(pool.size())]
    }
    return randomChars.join('')
  }
}
