################################################################################
# Automatically-generated file. Do not edit!
################################################################################

# Add inputs and outputs from these tool invocations to the build variables 
C_SRCS += \
../src/io/data/Batch.c \
../src/io/data/CsvData.c 

OBJS += \
./src/io/data/Batch.o \
./src/io/data/CsvData.o 

C_DEPS += \
./src/io/data/Batch.d \
./src/io/data/CsvData.d 


# Each subdirectory must supply rules for building sources it contributes
src/io/data/%.o: ../src/io/data/%.c
	@echo 'Building file: $<'
	@echo 'Invoking: GCC C Compiler'
	gcc -I"/home/elong/git/3.7/symmetric-ds/symmetric-client-clib/inc" -O0 -g3 -Wall -c -fmessage-length=0 -fPIC -MMD -MP -MF"$(@:%.o=%.d)" -MT"$(@:%.o=%.d)" -o "$@" "$<"
	@echo 'Finished building: $<'
	@echo ' '


