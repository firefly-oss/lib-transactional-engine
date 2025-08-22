# Contributing to Firefly Transactional Engine

We welcome contributions to the Firefly Transactional Engine! This document provides guidelines for contributing to the project.

## 🤝 How to Contribute

### Types of Contributions

We welcome several types of contributions:

- **Bug Reports**: Help us identify and fix issues
- **Feature Requests**: Suggest new features or improvements
- **Code Contributions**: Submit bug fixes, new features, or improvements
- **Documentation**: Improve or expand our documentation
- **Testing**: Add or improve test coverage
- **Performance**: Optimize performance or suggest improvements

## 🚀 Getting Started

### Prerequisites

Before you begin, ensure you have the following installed:

- **Java 17 or higher**
- **Maven 3.8+**
- **Docker** (for integration tests)
- **Git**

### Setting Up Development Environment

1. **Fork the Repository**
   ```bash
   # Fork the repo on GitHub, then clone your fork
   git clone https://github.com/YOUR_USERNAME/lib-transactional-engine.git
   cd lib-transactional-engine
   ```

2. **Add Upstream Remote**
   ```bash
   git remote add upstream https://github.com/firefly-oss/lib-transactional-engine.git
   ```

3. **Build the Project**
   ```bash
   mvn clean compile
   ```

4. **Run Tests**
   ```bash
   mvn test
   ```

5. **Run Integration Tests**
   ```bash
   mvn verify -P integration-tests
   ```

### Project Structure

```
lib-transactional-engine/
├── lib-transactional-engine-core/          # Core engine implementation
├── lib-transactional-engine-aws-starter/   # AWS integration
├── lib-transactional-engine-azure-starter/ # Azure integration  
├── lib-transactional-engine-inmemory-starter/ # In-memory implementation
├── docs/                                    # Documentation
├── examples/                                # Example projects
└── pom.xml                                  # Parent POM
```

## 📋 Development Guidelines

### Code Style

We follow standard Java coding conventions with some specific preferences:

- **Indentation**: 4 spaces (no tabs)
- **Line Length**: Maximum 120 characters
- **Naming**: Use descriptive names for classes, methods, and variables
- **Comments**: Use Javadoc for public APIs

### Code Formatting

We use Maven Checkstyle plugin for code formatting:

```bash
# Check code style
mvn checkstyle:check

# Auto-format code (if formatter is configured)
mvn spotless:apply
```

### Testing Guidelines

- **Unit Tests**: All new code must have unit tests with >80% coverage
- **Integration Tests**: Add integration tests for new features
- **Test Naming**: Use descriptive test method names
- **Test Structure**: Follow AAA pattern (Arrange, Act, Assert)

Example test:
```java
@Test
public void shouldExecuteSagaSuccessfullyWhenAllStepsComplete() {
    // Arrange
    var saga = createTestSaga();
    var inputs = StepInputs.of("orderId", "12345");
    
    // Act
    var result = sagaEngine.execute(saga, inputs).block();
    
    // Assert
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getSteps()).hasSize(3);
}
```

### Documentation Guidelines

- **Code Documentation**: Use Javadoc for public APIs
- **README Updates**: Update relevant README files for new features
- **User Documentation**: Add or update user-facing documentation in `docs/`
- **Changelog**: Add entries to CHANGELOG.md for significant changes

## 🐛 Reporting Issues

### Before Submitting an Issue

1. **Check Existing Issues**: Search existing issues to avoid duplicates
2. **Check Documentation**: Review the documentation for solutions
3. **Test with Latest Version**: Ensure you're using the latest version

### Issue Templates

Use our issue templates for:

- **Bug Report**: Include steps to reproduce, expected vs actual behavior
- **Feature Request**: Describe the feature and use case
- **Performance Issue**: Include performance metrics and environment details

### Bug Report Checklist

- [ ] Clear, descriptive title
- [ ] Steps to reproduce the issue
- [ ] Expected behavior vs actual behavior
- [ ] Environment details (Java version, OS, etc.)
- [ ] Relevant logs or error messages
- [ ] Minimal reproducible example (if possible)

## 🔧 Pull Request Process

### Before Submitting a Pull Request

1. **Create an Issue**: Discuss significant changes in an issue first
2. **Follow Branching Strategy**: Create a feature branch from `main`
3. **Write Tests**: Ensure adequate test coverage
4. **Update Documentation**: Update relevant documentation
5. **Test Locally**: Run all tests and ensure they pass

### Branching Strategy

```bash
# Create a feature branch
git checkout -b feature/your-feature-name

# Create a bugfix branch
git checkout -b bugfix/issue-number-description

# Create a documentation branch
git checkout -b docs/improvement-description
```

### Pull Request Guidelines

- **Descriptive Title**: Use a clear, concise title
- **Detailed Description**: Explain what changes were made and why
- **Link Issues**: Reference related issues using "Fixes #123" or "Closes #123"
- **Small, Focused Changes**: Keep PRs focused and reasonably sized
- **Clean History**: Rebase and squash commits if necessary

### Pull Request Template

When creating a PR, include:

```markdown
## Description
Brief description of the changes made.

## Type of Change
- [ ] Bug fix (non-breaking change which fixes an issue)
- [ ] New feature (non-breaking change which adds functionality)
- [ ] Breaking change (fix or feature that would cause existing functionality to not work as expected)
- [ ] Documentation update

## Related Issues
Fixes #(issue number)

## Testing
- [ ] Unit tests added/updated
- [ ] Integration tests added/updated
- [ ] All tests pass locally

## Documentation
- [ ] Documentation updated
- [ ] Javadoc added/updated
- [ ] README updated (if needed)

## Checklist
- [ ] Code follows project style guidelines
- [ ] Self-review completed
- [ ] Comments added for complex logic
- [ ] No new compiler warnings
```

### Code Review Process

1. **Automated Checks**: Ensure all CI checks pass
2. **Peer Review**: At least one maintainer will review your PR
3. **Feedback**: Address review comments promptly
4. **Approval**: PR must be approved before merging
5. **Merge**: Maintainers will merge approved PRs

## 🏗️ Development Workflow

### Setting Up Your Branch

```bash
# Update your local main branch
git checkout main
git pull upstream main

# Create your feature branch
git checkout -b feature/your-feature-name

# Make your changes and commit
git add .
git commit -m "feat: add new saga compensation strategy"

# Push to your fork
git push origin feature/your-feature-name
```

### Commit Message Guidelines

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```bash
# Format: <type>[optional scope]: <description>

feat: add support for Azure Event Hubs integration
fix: resolve memory leak in saga instance cache
docs: update getting started guide with examples
test: add integration tests for compensation flow
refactor: simplify step execution pipeline
perf: optimize database query performance
```

Types:
- `feat`: New features
- `fix`: Bug fixes
- `docs`: Documentation changes
- `test`: Adding or fixing tests
- `refactor`: Code refactoring
- `perf`: Performance improvements
- `chore`: Maintenance tasks

### Testing Your Changes

```bash
# Run unit tests
mvn test

# Run integration tests
mvn verify -P integration-tests

# Run specific test class
mvn test -Dtest=SagaEngineTest

# Run tests with coverage
mvn verify -P coverage

# Check code style
mvn checkstyle:check

# Run full build
mvn clean verify
```

## 📚 Documentation Contributions

### Documentation Types

- **User Documentation**: Guides and tutorials in `docs/`
- **API Documentation**: Javadoc comments in code
- **README Files**: Module-specific documentation
- **Examples**: Working examples in `examples/`

### Writing Guidelines

- **Clear and Concise**: Use simple, direct language
- **Code Examples**: Include working code examples
- **Step-by-Step**: Provide detailed instructions
- **Screenshots**: Add screenshots for UI-related features (if any)

### Documentation Build

```bash
# Generate Javadoc
mvn javadoc:javadoc

# Build site documentation
mvn site

# Preview locally (if using GitHub Pages)
bundle exec jekyll serve
```

## 🚀 Release Process

### Versioning

We follow [Semantic Versioning](https://semver.org/):
- **Major**: Breaking changes (x.0.0)
- **Minor**: New features, backward compatible (0.x.0)
- **Patch**: Bug fixes, backward compatible (0.0.x)

### Release Checklist (for Maintainers)

- [ ] All tests pass
- [ ] Documentation updated
- [ ] CHANGELOG.md updated
- [ ] Version numbers updated
- [ ] Release notes prepared
- [ ] Tag created and pushed
- [ ] Artifacts published

## 🤝 Community Guidelines

### Code of Conduct

We are committed to providing a welcoming and inclusive experience for everyone. Please read and follow our [Code of Conduct](CODE_OF_CONDUCT.md).

### Communication Channels

- **GitHub Issues**: Bug reports and feature requests
- **GitHub Discussions**: General questions and discussions
- **Pull Requests**: Code review and collaboration

### Getting Help

- **Documentation**: Check the `docs/` directory
- **Examples**: Look at the `examples/` directory
- **Issues**: Search existing issues or create a new one
- **Discussions**: Use GitHub Discussions for questions

## 🏆 Recognition

### Contributors

All contributors will be:
- Listed in the project's contributor list
- Mentioned in release notes for their contributions
- Invited to join the project's contributor team (for regular contributors)

### Types of Recognition

- **First-time contributors**: Welcomed with special recognition
- **Regular contributors**: Added to contributor team
- **Major contributions**: Featured in release announcements
- **Long-term maintainers**: Given additional permissions and responsibilities

## 📝 Additional Resources

### Development Tools

Recommended tools for development:
- **IDE**: IntelliJ IDEA or Eclipse
- **Code Style**: Import code style from `.editorconfig`
- **Git**: Use Git hooks for pre-commit checks
- **Testing**: TestContainers for integration tests

### Learning Resources

- [Saga Pattern](https://microservices.io/patterns/data/saga.html)
- [Project Reactor](https://projectreactor.io/docs)
- [Spring Boot](https://spring.io/projects/spring-boot)
- [Reactive Streams](https://www.reactive-streams.org/)

### Maven Commands Reference

```bash
# Clean build
mvn clean compile

# Run tests
mvn test

# Package without tests
mvn package -DskipTests

# Install to local repository
mvn install

# Run with specific profile
mvn test -P integration-tests

# Generate dependency tree
mvn dependency:tree

# Check for updates
mvn versions:display-dependency-updates
```

---

## 🙏 Thank You

Thank you for contributing to the Firefly Transactional Engine! Your contributions help make distributed transaction management better for everyone.

If you have any questions about contributing, please don't hesitate to reach out through GitHub Issues or Discussions.

**Happy coding!** 🚀