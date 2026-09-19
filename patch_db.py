with open('app/src/main/java/com/example/data/AppDatabase.kt', 'r') as f:
    content = f.read()

content = content.replace("entities = [ChatEntity::class, MessageEntity::class], version = 1", "entities = [ChatEntity::class, MessageEntity::class, MemoryEntity::class], version = 2")
content = content.replace("abstract fun chatDao(): ChatDao", "abstract fun chatDao(): ChatDao\n    abstract fun memoryDao(): MemoryDao")
content = content.replace(".build()", ".fallbackToDestructiveMigration().build()")

with open('app/src/main/java/com/example/data/AppDatabase.kt', 'w') as f:
    f.write(content)
print("DB Patch applied")
